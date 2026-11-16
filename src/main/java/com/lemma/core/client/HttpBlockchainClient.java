package com.lemma.core.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cliente HTTP do Blockchain Service (seção 7.1 do desenho), com o HttpClient do JDK.
 *
 * Toda chamada leva X-Service-Key. Tempo limite de conexão de 3 s e de resposta
 * configurável (BLOCKCHAIN_TIMEOUT_SECONDS), menos o CT14, que tem tempo fixo de
 * 5 s (a falha dele é tolerada). Qualquer resposta que não seja a
 * esperada vira {@link BlockchainUnavailableException}; a exceção é a recusa do
 * CT10 (400 ADDRESS_INVALID ou AMOUNT_INVALID), que vira {@link BlockchainRejectedException}.
 *
 * O log traz só o contrato e o status ou o code devolvido: nunca o corpo, a
 * X-Service-Key nem a URL. Das exceções de rede, só o nome da classe (a mensagem
 * pode trazer o host).
 */
public class HttpBlockchainClient implements BlockchainClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBlockchainClient.class);

    /**
     * Lê decimais como BigDecimal exato. Sem USE_BIG_DECIMAL_FOR_FLOATS o Jackson
     * usaria double e perderia dígitos (1 wei = 0.000000000000000001). Sem desligar
     * STRIP_TRAILING_BIGDECIMAL_ZEROES, a árvore trocaria 0.000000000000000000 por 0.
     * Na escrita (corpo do CT10), BigDecimal sai em notação plana: 0.000000000000000001,
     * nunca 1E-18.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Tempo de resposta do CT14, fixo (seção 7.1 do desenho): a consulta ativa roda
     * dentro do polling do Frontend, e a falha dela só devolve o estado local.
     */
    static final Duration GET_TASK_TIMEOUT = Duration.ofSeconds(5);

    private static final String CT4 = "CT4 createWallet";
    private static final String CT7 = "CT7 getWalletBalance";
    private static final String CT10 = "CT10 requestSubmission";
    private static final String CT14 = "CT14 getTask";

    /** O CT7 converte de wei para ETH: nunca mais de 18 casas. */
    private static final int MAX_ETH_SCALE = 18;

    private static final String NETWORK = "SEPOLIA";

    /**
     * Endereço Ethereum: 0x e 40 hexadecimais (cabe no VARCHAR(42) do app_user).
     * Também protege o caminho do CT7: nada de "/", "..", "?" ou "%" na URL.
     */
    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** Só um code nesse formato vai ao log; qualquer outra coisa do corpo, nunca. */
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z_]{1,64}$");

    /** taskId do CT10: UUID em texto, que cabe no CHAR(36) do eth_transaction. */
    private static final Pattern TASK_ID = Pattern.compile("^[0-9a-fA-F-]{36}$");

    /** Hash de transação: 0x e 64 hexadecimais (cabe no VARCHAR(66) do eth_transaction). */
    private static final Pattern TRANSACTION_HASH = Pattern.compile("^0x[0-9a-fA-F]{64}$");

    /**
     * Codes que indicam erro de configuração ou de dado, não indisponibilidade: ERROR
     * no log. No CT4 e no CT7, o endereço vem do app_user, então ADDRESS_INVALID é
     * erro de dado.
     */
    private static final Set<String> CONFIGURATION_OR_DATA_ERRORS =
            Set.of("SERVICE_KEY_INVALID", "WALLET_NOT_FOUND", "ADDRESS_INVALID");

    /**
     * No CT10, REQUEST_INVALID indicaria um corpo mal montado pelo próprio Core API.
     * ADDRESS_INVALID e AMOUNT_INVALID são recusas do pedido do usuário: WARN.
     */
    private static final Set<String> CT10_CONFIGURATION_OR_DATA_ERRORS =
            Set.of("SERVICE_KEY_INVALID", "WALLET_NOT_FOUND", "REQUEST_INVALID");

    /** Recusas do CT10 repassadas ao usuário com o mesmo code (lista fechada). */
    private static final Set<String> CT10_REPASSED_REJECTIONS = Set.of("ADDRESS_INVALID", "AMOUNT_INVALID");

    private final String baseUrl;
    private final String serviceKey;
    private final Duration responseTimeout;
    private final Duration getTaskTimeout;
    private final HttpClient httpClient;

    /** baseUrl sem barra final (o AppConfig já remove). O CT14 usa {@link #GET_TASK_TIMEOUT}. */
    public HttpBlockchainClient(String baseUrl, String serviceKey, Duration responseTimeout) {
        this(baseUrl, serviceKey, responseTimeout, GET_TASK_TIMEOUT);
    }

    /** Só para os testes: troca o tempo do CT14, para não esperar 5 s. */
    HttpBlockchainClient(String baseUrl, String serviceKey, Duration responseTimeout, Duration getTaskTimeout) {
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.responseTimeout = responseTimeout;
        this.getTaskTimeout = getTaskTimeout;
        // HTTP/1.1: sem tentativa de upgrade para HTTP/2 em http:// simples.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** CT4: POST /wallets sem corpo → 201 { "ethereumAddress": "0x..." }. */
    @Override
    public String createWallet() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/wallets"))
                .timeout(responseTimeout)
                .header("X-Service-Key", serviceKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = send(CT4, request);

        if (response.statusCode() != 201) {
            logUnexpectedStatus(CT4, response, CONFIGURATION_OR_DATA_ERRORS);
            throw new BlockchainUnavailableException(CT4);
        }
        String address = readTextField(response.body(), "ethereumAddress");
        if (address == null || !ADDRESS.matcher(address).matches()) {
            log.warn("{}: HTTP 201 sem ethereumAddress válido no corpo", CT4);
            throw new BlockchainUnavailableException(CT4);
        }
        return address;
    }

    /**
     * CT7: GET /wallets/{ethereumAddress}/balance sem corpo →
     * 200 { "ethereumAddress", "balanceEth", "network" }.
     *
     * Só balanceEth e network são lidos: o endereço da resposta do CT6 é o do
     * app_user, o mesmo que vai no caminho.
     */
    @Override
    public BalanceResult getBalance(String ethereumAddress) {
        // Antes de montar a URL: o endereço vai no caminho.
        if (ethereumAddress == null || !ADDRESS.matcher(ethereumAddress).matches()) {
            log.error("{}: endereço da carteira fora do formato; a chamada não foi feita", CT7);
            throw new BlockchainUnavailableException(CT7);
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/wallets/" + ethereumAddress + "/balance"))
                .timeout(responseTimeout)
                .header("X-Service-Key", serviceKey)
                .GET()
                .build();

        HttpResponse<String> response = send(CT7, request);

        if (response.statusCode() != 200) {
            logUnexpectedStatus(CT7, response, CONFIGURATION_OR_DATA_ERRORS);
            throw new BlockchainUnavailableException(CT7);
        }
        BalanceResult result = readBalance(response.body());
        if (result == null) {
            log.warn("{}: HTTP 200 com corpo fora do formato", CT7);
            throw new BlockchainUnavailableException(CT7);
        }
        return result;
    }

    /**
     * Confere o corpo do CT7: objeto JSON; balanceEth número (nunca texto ou null),
     * não negativo, com 0 a 18 casas; network texto igual a SEPOLIA. Devolve null
     * se qualquer coisa estiver fora disso.
     *
     * A escala de 0 a 18 também recusa expoentes absurdos (1E+999999999), que a
     * escrita plana da resposta não conseguiria serializar.
     */
    private static BalanceResult readBalance(String body) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode balanceNode = root.path("balanceEth");
        JsonNode networkNode = root.path("network");
        if (!balanceNode.isNumber() || !networkNode.isTextual() || !NETWORK.equals(networkNode.asText())) {
            return null;
        }
        BigDecimal balanceEth = balanceNode.decimalValue();
        if (balanceEth.signum() < 0 || balanceEth.scale() < 0 || balanceEth.scale() > MAX_ETH_SCALE) {
            return null;
        }
        return new BalanceResult(balanceEth, NETWORK);
    }

    /**
     * CT10: POST /transactions com { sourceAddress, destinationAddress, amountEth } →
     * 202 { "taskId", "status": "PENDING" }. amountEth vai como número JSON em
     * notação plana (0.01, 0.000000000000000001, 100). Uma única tentativa.
     *
     * 400 ADDRESS_INVALID ou AMOUNT_INVALID vira {@link BlockchainRejectedException};
     * qualquer outra coisa que não seja o 202 esperado vira
     * {@link BlockchainUnavailableException}. Endereços e valor nunca vão ao log.
     */
    @Override
    public String requestSubmission(String sourceAddress, String destinationAddress, BigDecimal amountEth) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/transactions"))
                .timeout(responseTimeout)
                .header("X-Service-Key", serviceKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        submissionBody(sourceAddress, destinationAddress, amountEth)))
                .build();

        HttpResponse<String> response = send(CT10, request);

        if (response.statusCode() != 202) {
            String code = logUnexpectedStatus(CT10, response, CT10_CONFIGURATION_OR_DATA_ERRORS);
            if (response.statusCode() == 400 && CT10_REPASSED_REJECTIONS.contains(code)) {
                throw new BlockchainRejectedException(code);
            }
            throw new BlockchainUnavailableException(CT10);
        }
        String taskId = readAcceptedTaskId(response.body());
        if (taskId == null) {
            log.warn("{}: HTTP 202 com corpo fora do formato", CT10);
            throw new BlockchainUnavailableException(CT10);
        }
        return taskId;
    }

    /** Corpo do CT10, na ordem da ficha. */
    record SubmissionBody(String sourceAddress, String destinationAddress, BigDecimal amountEth) {
    }

    private static String submissionBody(String sourceAddress, String destinationAddress, BigDecimal amountEth) {
        try {
            return JSON.writeValueAsString(new SubmissionBody(sourceAddress, destinationAddress, amountEth));
        } catch (JsonProcessingException e) {
            // Um record de textos e um BigDecimal sempre serializam; não há dado do usuário nesta mensagem.
            throw new IllegalStateException("falha ao montar o corpo do CT10", e);
        }
    }

    /**
     * Confere o corpo do 202: objeto JSON; taskId texto no formato de UUID (36
     * caracteres); status texto igual a PENDING. Devolve o taskId, ou null se
     * qualquer coisa estiver fora disso.
     */
    private static String readAcceptedTaskId(String body) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode taskIdNode = root.path("taskId");
        JsonNode statusNode = root.path("status");
        if (!taskIdNode.isTextual() || !TASK_ID.matcher(taskIdNode.asText()).matches()
                || !statusNode.isTextual() || !"PENDING".equals(statusNode.asText())) {
            return null;
        }
        return taskIdNode.asText();
    }

    /**
     * CT14: GET /tasks/{taskId} sem corpo →
     * 200 { taskId, status, transactionHash?, failureReason?, updatedAt }.
     *
     * Qualquer status diferente de 200 (inclusive 404 TASK_NOT_FOUND) e qualquer
     * corpo fora do formato viram {@link BlockchainUnavailableException}. Só
     * SERVICE_KEY_INVALID sai como ERROR; os demais, WARN (a consulta se repete a
     * cada polling). O taskId nunca vai ao log.
     */
    @Override
    public TaskState getTask(String taskId) {
        // Antes de montar a URL: o taskId vai no caminho.
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
            log.error("{}: taskId fora do formato; a chamada não foi feita", CT14);
            throw new BlockchainUnavailableException(CT14);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/tasks/" + taskId))
                .timeout(getTaskTimeout)
                .header("X-Service-Key", serviceKey)
                .GET()
                .build();

        HttpResponse<String> response = send(CT14, request);

        if (response.statusCode() != 200) {
            logUnexpectedStatus(CT14, response, Set.of());
            throw new BlockchainUnavailableException(CT14);
        }
        TaskState state = readTaskState(response.body(), taskId);
        if (state == null) {
            log.warn("{}: HTTP 200 com corpo fora do formato", CT14);
            throw new BlockchainUnavailableException(CT14);
        }
        return state;
    }

    /**
     * Confere o corpo do CT14: objeto JSON; taskId texto igual ao pedido (sem
     * diferença de caixa); status texto exatamente PENDING, CONFIRMED ou FAILED;
     * transactionHash ausente, null ou texto no formato do hash; failureReason
     * ausente, null ou um valor do enum. Coerência: FAILED exige motivo, PENDING e
     * CONFIRMED não o aceitam, e CONFIRMED exige hash. O updatedAt não é lido.
     * Devolve null se qualquer coisa estiver fora disso.
     */
    private static TaskState readTaskState(String body, String taskId) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode taskIdNode = root.path("taskId");
        if (!taskIdNode.isTextual() || !taskId.equalsIgnoreCase(taskIdNode.asText())) {
            return null;
        }
        TransactionStatus status = enumValue(root.path("status"), TransactionStatus.class);
        if (status == null) {
            return null;
        }

        JsonNode hashNode = root.path("transactionHash");
        String transactionHash = null;
        if (!isAbsent(hashNode)) {
            if (!hashNode.isTextual() || !TRANSACTION_HASH.matcher(hashNode.asText()).matches()) {
                return null;
            }
            transactionHash = hashNode.asText();
        }

        JsonNode reasonNode = root.path("failureReason");
        FailureReason failureReason = null;
        if (!isAbsent(reasonNode)) {
            failureReason = enumValue(reasonNode, FailureReason.class);
            if (failureReason == null) {
                return null;
            }
        }

        boolean failed = status == TransactionStatus.FAILED;
        if (failed != (failureReason != null)) {
            return null;
        }
        if (status == TransactionStatus.CONFIRMED && transactionHash == null) {
            return null;
        }
        return new TaskState(status, transactionHash, failureReason);
    }

    /** Campo ausente ou null. */
    private static boolean isAbsent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    /** Texto igual, com a mesma caixa, ao nome de um valor do enum; senão null. */
    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type) {
        if (!node.isTextual()) {
            return null;
        }
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(node.asText())) {
                return value;
            }
        }
        return null;
    }

    private HttpResponse<String> send(String contract, HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // devolve o pedido de interrupção a quem chamou
            log.warn("{}: chamada interrompida", contract);
            throw new BlockchainUnavailableException(contract);
        } catch (IOException e) {
            // Conexão recusada, host inexistente, tempo esgotado (HttpTimeoutException).
            log.warn("{}: falha de rede ({})", contract, e.getClass().getSimpleName());
            throw new BlockchainUnavailableException(contract);
        }
    }

    /**
     * Loga o status e o code devolvidos. SERVICE_KEY_INVALID (chaves diferentes nos
     * dois módulos) e os codes de errorCodes são erros de configuração ou de dado:
     * ERROR. Os demais, WARN. Devolve o code lido (ou "sem code").
     */
    private static String logUnexpectedStatus(String contract, HttpResponse<String> response,
                                              Set<String> errorCodes) {
        String code = readTextField(response.body(), "code");
        if (code == null || !ERROR_CODE.matcher(code).matches()) {
            code = "sem code";
        }
        if ("SERVICE_KEY_INVALID".equals(code)) {
            log.error("{}: HTTP {} {}; a SERVICE_KEY difere da configurada no Blockchain Service",
                    contract, response.statusCode(), code);
        } else if (errorCodes.contains(code)) {
            log.error("{}: HTTP {} {}; erro de configuração ou de dado", contract, response.statusCode(), code);
        } else {
            log.warn("{}: HTTP {} {}", contract, response.statusCode(), code);
        }
        return code;
    }

    /** Lê um campo de texto do corpo JSON; devolve null se o corpo for ilegível ou o campo faltar. */
    private static String readTextField(String body, String field) {
        JsonNode root = readJson(body);
        if (root == null) {
            return null;
        }
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    /** O corpo como árvore JSON, ou null se for ilegível. Corpo vazio vira MissingNode. */
    private static JsonNode readJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
