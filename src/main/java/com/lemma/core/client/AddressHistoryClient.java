package com.lemma.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cliente do histórico por endereço no External Data Service (CT24, seção 7.2 do
 * desenho), com o HttpClient do JDK e o mesmo padrão do {@link MarketDataClient}.
 *
 * CT24: GET /addresses/{ethereumAddress}/transactions com X-Service-Key. Tempo
 * limite de conexão de 3 s e de resposta configurável (EXTERNAL_DATA_TIMEOUT_SECONDS).
 * Uma única tentativa. Qualquer resposta que não seja 200 com o corpo no formato
 * vira {@link HistoryUnavailableException}: nunca uma lista parcial.
 *
 * O log traz só o contrato e o status ou o code devolvido: nunca o corpo, a
 * X-Service-Key, a URL nem o endereço. Das exceções de rede, só o nome da classe
 * (a mensagem pode trazer o host).
 */
public class AddressHistoryClient {

    private static final Logger log = LoggerFactory.getLogger(AddressHistoryClient.class);

    /**
     * Só lê a árvore. O amountEth vem em texto e vira new BigDecimal(texto); número
     * JSON no lugar dele é corpo fora do formato, então nenhum valor passa por double.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final String CT24 = "CT24 fetchAddressTransactions";

    private static final String NETWORK = "SEPOLIA";

    /** O CT24 devolve só a primeira página do Blockscout: até 20 transações. */
    static final int MAX_TRANSACTIONS = 20;

    /** Endereço Ethereum. Também protege o caminho do CT24: nada de "/", "..", "?" ou "%". */
    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private static final Pattern HASH = Pattern.compile("^0x[0-9a-fA-F]{64}$");

    /**
     * Valor em ETH em texto, notação plana, sem sinal nem expoente: até 20 dígitos
     * inteiros e até 18 casas (o wei tem 18 casas; os limites são os do CT9 e da
     * coluna DECIMAL(38,18)).
     */
    private static final Pattern AMOUNT = Pattern.compile("^[0-9]{1,20}(\\.[0-9]{1,18})?$");

    private static final Set<String> DIRECTIONS = Set.of("SENT", "RECEIVED", "SELF");

    private static final Set<String> STATUSES = Set.of("CONFIRMED", "FAILED");

    /** Só um code nesse formato vai ao log; qualquer outra coisa do corpo, nunca. */
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z_]{1,64}$");

    private final String baseUrl;
    private final String serviceKey;
    private final Duration responseTimeout;
    private final HttpClient httpClient;

    /** baseUrl sem barra final (o AppConfig já remove). */
    public AddressHistoryClient(String baseUrl, String serviceKey, Duration responseTimeout) {
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.responseTimeout = responseTimeout;
        // HTTP/1.1: sem tentativa de upgrade para HTTP/2 em http:// simples.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * CT24: GET /addresses/{ethereumAddress}/transactions sem corpo →
     * 200 { ethereumAddress, network, transactions: [ ... ] }. Devolve as
     * transações na ordem recebida (mais recentes primeiro); lista vazia se o
     * endereço não tiver transações.
     */
    public List<AddressTransaction> fetchAddressTransactions(String ethereumAddress) {
        // Antes de montar a URL: o endereço vai no caminho.
        if (ethereumAddress == null || !ADDRESS.matcher(ethereumAddress).matches()) {
            log.error("{}: endereço fora do formato; a chamada não foi feita", CT24);
            throw new HistoryUnavailableException(CT24);
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/addresses/" + ethereumAddress + "/transactions"))
                .timeout(responseTimeout)
                .header("X-Service-Key", serviceKey)
                .GET()
                .build();

        HttpResponse<String> response = send(request);

        if (response.statusCode() != 200) {
            logUnexpectedStatus(response.statusCode(), readCode(response.body()));
            throw new HistoryUnavailableException(CT24);
        }
        List<AddressTransaction> transactions = readHistory(response.body(), ethereumAddress);
        if (transactions == null) {
            log.warn("{}: HTTP 200 com corpo fora do formato", CT24);
            throw new HistoryUnavailableException(CT24);
        }
        return transactions;
    }

    /**
     * Confere o corpo do CT24: objeto JSON; ethereumAddress texto igual ao pedido,
     * sem diferenciar maiúsculas; network exatamente SEPOLIA; transactions lista de
     * no máximo {@link #MAX_TRANSACTIONS} itens válidos ({@link #readTransaction}).
     * Um item inválido invalida o corpo todo. Campos extras são ignorados. Devolve
     * null se qualquer coisa estiver fora disso.
     */
    private static List<AddressTransaction> readHistory(String body, String ethereumAddress) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode addressNode = root.path("ethereumAddress");
        JsonNode networkNode = root.path("network");
        if (!addressNode.isTextual() || !ethereumAddress.equalsIgnoreCase(addressNode.asText())
                || !networkNode.isTextual() || !NETWORK.equals(networkNode.asText())) {
            return null;
        }
        JsonNode transactionsNode = root.path("transactions");
        if (!transactionsNode.isArray() || transactionsNode.size() > MAX_TRANSACTIONS) {
            return null;
        }
        List<AddressTransaction> transactions = new ArrayList<>(transactionsNode.size());
        for (JsonNode itemNode : transactionsNode) {
            AddressTransaction transaction = readTransaction(itemNode);
            if (transaction == null) {
                return null;
            }
            transactions.add(transaction);
        }
        return List.copyOf(transactions);
    }

    /**
     * Um item: objeto com hash (0x + 64 hexadecimais), direction (SENT, RECEIVED ou
     * SELF), counterpartyAddress presente (null ou endereço), amountEth texto
     * ({@link #AMOUNT}), timestamp texto aceito por Instant.parse e status
     * (CONFIRMED ou FAILED). Senão null.
     */
    private static AddressTransaction readTransaction(JsonNode itemNode) {
        if (!itemNode.isObject()) {
            return null;
        }
        String hash = readText(itemNode.path("hash"));
        String direction = readText(itemNode.path("direction"));
        String amountEth = readText(itemNode.path("amountEth"));
        String timestamp = readText(itemNode.path("timestamp"));
        String status = readText(itemNode.path("status"));
        if (hash == null || !HASH.matcher(hash).matches()
                || direction == null || !DIRECTIONS.contains(direction)
                || amountEth == null || !AMOUNT.matcher(amountEth).matches()
                || timestamp == null
                || status == null || !STATUSES.contains(status)) {
            return null;
        }

        // Obrigatório, mas pode ser null; ausente (MissingNode) é fora do formato.
        JsonNode counterpartyNode = itemNode.path("counterpartyAddress");
        String counterpartyAddress;
        if (counterpartyNode.isNull()) {
            counterpartyAddress = null;
        } else if (counterpartyNode.isTextual() && ADDRESS.matcher(counterpartyNode.asText()).matches()) {
            counterpartyAddress = counterpartyNode.asText();
        } else {
            return null;
        }

        Instant instant;
        try {
            instant = Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
        return new AddressTransaction(hash, direction, counterpartyAddress, new BigDecimal(amountEth),
                instant, status);
    }

    /** O texto de um nó, só se ele for texto JSON; senão null (número, null, ausente). */
    private static String readText(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // devolve o pedido de interrupção a quem chamou
            log.warn("{}: chamada interrompida", CT24);
            throw new HistoryUnavailableException(CT24);
        } catch (IOException e) {
            // Conexão recusada, host inexistente, tempo esgotado (HttpTimeoutException).
            log.warn("{}: falha de rede ({})", CT24, e.getClass().getSimpleName());
            throw new HistoryUnavailableException(CT24);
        }
    }

    /**
     * Loga o status e o code devolvidos. SERVICE_KEY_INVALID (chaves diferentes nos
     * dois módulos) é erro de configuração: ERROR. Os demais, WARN.
     */
    private static void logUnexpectedStatus(int status, String code) {
        if ("SERVICE_KEY_INVALID".equals(code)) {
            log.error("{}: HTTP {} {}; a SERVICE_KEY difere da configurada no External Data Service",
                    CT24, status, code);
        } else {
            log.warn("{}: HTTP {} {}", CT24, status, code == null ? "sem code" : code);
        }
    }

    /** O code de um corpo de erro, só se tiver o formato [A-Z_]; senão null. */
    private static String readCode(String body) {
        JsonNode root = readJson(body);
        if (root == null) {
            return null;
        }
        JsonNode node = root.path("code");
        if (!node.isTextual() || !ERROR_CODE.matcher(node.asText()).matches()) {
            return null;
        }
        return node.asText();
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
