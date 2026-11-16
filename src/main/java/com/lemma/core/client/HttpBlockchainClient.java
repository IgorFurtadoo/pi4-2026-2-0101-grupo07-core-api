package com.lemma.core.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
 * configurável (BLOCKCHAIN_TIMEOUT_SECONDS). Qualquer resposta que não seja a
 * esperada vira {@link BlockchainUnavailableException}.
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
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final String CT4 = "CT4 createWallet";
    private static final String CT7 = "CT7 getWalletBalance";

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

    /** Codes que indicam erro de configuração ou de dado, não indisponibilidade: ERROR no log. */
    private static final Set<String> CONFIGURATION_OR_DATA_ERRORS =
            Set.of("SERVICE_KEY_INVALID", "WALLET_NOT_FOUND", "ADDRESS_INVALID");

    private final String baseUrl;
    private final String serviceKey;
    private final Duration responseTimeout;
    private final HttpClient httpClient;

    /** baseUrl sem barra final (o AppConfig já remove). */
    public HttpBlockchainClient(String baseUrl, String serviceKey, Duration responseTimeout) {
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.responseTimeout = responseTimeout;
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
            logUnexpectedStatus(CT4, response);
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
            logUnexpectedStatus(CT7, response);
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
     * SERVICE_KEY_INVALID (chaves diferentes nos dois módulos), WALLET_NOT_FOUND e
     * ADDRESS_INVALID (endereço do app_user que o Blockchain Service não reconhece)
     * são erros de configuração ou de dado: ERROR. Os demais, WARN. Para o usuário,
     * a resposta é a mesma.
     */
    private static void logUnexpectedStatus(String contract, HttpResponse<String> response) {
        String code = readTextField(response.body(), "code");
        if (code == null || !ERROR_CODE.matcher(code).matches()) {
            code = "sem code";
        }
        if ("SERVICE_KEY_INVALID".equals(code)) {
            log.error("{}: HTTP {} {}; a SERVICE_KEY difere da configurada no Blockchain Service",
                    contract, response.statusCode(), code);
        } else if (CONFIGURATION_OR_DATA_ERRORS.contains(code)) {
            log.error("{}: HTTP {} {}; erro de configuração ou de dado", contract, response.statusCode(), code);
        } else {
            log.warn("{}: HTTP {} {}", contract, response.statusCode(), code);
        }
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
