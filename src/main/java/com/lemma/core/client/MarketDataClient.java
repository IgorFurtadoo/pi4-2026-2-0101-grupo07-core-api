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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cliente HTTP do External Data Service (seção 7.2 do desenho), com o HttpClient do JDK.
 *
 * CT18: GET /market-data/{assetId} com X-Service-Key. Tempo limite de conexão de
 * 3 s e de resposta configurável (EXTERNAL_DATA_TIMEOUT_SECONDS). Uma única tentativa.
 * 404 ASSET_NOT_FOUND vira {@link AssetNotFoundException}; qualquer outra resposta
 * que não seja a esperada vira {@link MarketDataUnavailableException}.
 *
 * O log traz só o contrato e o status ou o code devolvido: nunca o corpo, a
 * X-Service-Key, a URL nem o assetId. Das exceções de rede, só o nome da classe
 * (a mensagem pode trazer o host). O 404 ASSET_NOT_FOUND não vai ao log.
 */
public class MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

    /**
     * Lê decimais como BigDecimal exato, com a mesma configuração de leitura do
     * HttpBlockchainClient: sem USE_BIG_DECIMAL_FOR_FLOATS o Jackson usaria double e
     * perderia dígitos; sem desligar STRIP_TRAILING_BIGDECIMAL_ZEROES, a árvore
     * trocaria 3200.450 por 3200.45. Este cliente não escreve JSON.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final String CT18 = "CT18 fetchMarketData";

    private static final String CURRENCY = "USD";

    /**
     * assetId válido (seção 6 do desenho). Também protege o caminho do CT18: nada
     * de "/", "..", "?" ou "%" na URL.
     */
    private static final Pattern ASSET_ID = Pattern.compile("^[a-z0-9-]{1,64}$");

    /** Só um code nesse formato vai ao log; qualquer outra coisa do corpo, nunca. */
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z_]{1,64}$");

    /**
     * Limites de um preço: até 30 casas (ativos baratos têm muitas) e até 20
     * dígitos inteiros. Recusam expoentes absurdos (1E+999999999, 1E-999999999),
     * que a escrita em notação plana da resposta não conseguiria serializar.
     */
    static final int MAX_PRICE_SCALE = 30;
    static final int MAX_PRICE_INTEGER_DIGITS = 20;

    private final String baseUrl;
    private final String serviceKey;
    private final Duration responseTimeout;
    private final HttpClient httpClient;

    /** baseUrl sem barra final (o AppConfig já remove). */
    public MarketDataClient(String baseUrl, String serviceKey, Duration responseTimeout) {
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
     * CT18: GET /market-data/{assetId} sem corpo →
     * 200 { assetId, currency, currentPrice, series: [ { timestamp, price } ] }.
     */
    public MarketData fetchMarketData(String assetId) {
        // Antes de montar a URL: o assetId vai no caminho.
        if (assetId == null || !ASSET_ID.matcher(assetId).matches()) {
            log.error("{}: assetId fora do formato; a chamada não foi feita", CT18);
            throw new MarketDataUnavailableException(CT18);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/market-data/" + assetId))
                .timeout(responseTimeout)
                .header("X-Service-Key", serviceKey)
                .GET()
                .build();

        HttpResponse<String> response = send(request);

        if (response.statusCode() != 200) {
            String code = readCode(response.body());
            if (response.statusCode() == 404 && "ASSET_NOT_FOUND".equals(code)) {
                throw new AssetNotFoundException();
            }
            logUnexpectedStatus(response.statusCode(), code);
            throw new MarketDataUnavailableException(CT18);
        }
        MarketData data = readMarketData(response.body(), assetId);
        if (data == null) {
            log.warn("{}: HTTP 200 com corpo fora do formato", CT18);
            throw new MarketDataUnavailableException(CT18);
        }
        return data;
    }

    /**
     * Confere o corpo do CT18: objeto JSON; assetId texto igual ao pedido; currency
     * texto exatamente USD; currentPrice preço válido ({@link #readPrice}); series
     * lista não vazia de objetos com timestamp texto aceito por Instant.parse e
     * price válido. Um ponto inválido invalida o corpo todo. Campos extras são
     * ignorados. Devolve null se qualquer coisa estiver fora disso.
     */
    private static MarketData readMarketData(String body, String assetId) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode assetIdNode = root.path("assetId");
        JsonNode currencyNode = root.path("currency");
        if (!assetIdNode.isTextual() || !assetId.equals(assetIdNode.asText())
                || !currencyNode.isTextual() || !CURRENCY.equals(currencyNode.asText())) {
            return null;
        }
        BigDecimal currentPrice = readPrice(root.path("currentPrice"));
        if (currentPrice == null) {
            return null;
        }
        JsonNode seriesNode = root.path("series");
        if (!seriesNode.isArray() || seriesNode.isEmpty()) {
            return null;
        }
        List<PricePoint> series = new ArrayList<>(seriesNode.size());
        for (JsonNode pointNode : seriesNode) {
            PricePoint point = readPoint(pointNode);
            if (point == null) {
                return null;
            }
            series.add(point);
        }
        return new MarketData(assetId, CURRENCY, currentPrice, List.copyOf(series));
    }

    /** Um ponto da série: objeto com timestamp (Instant.parse) e price válidos; senão null. */
    private static PricePoint readPoint(JsonNode pointNode) {
        if (!pointNode.isObject()) {
            return null;
        }
        JsonNode timestampNode = pointNode.path("timestamp");
        if (!timestampNode.isTextual()) {
            return null;
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampNode.asText());
        } catch (DateTimeParseException e) {
            return null;
        }
        BigDecimal price = readPrice(pointNode.path("price"));
        if (price == null) {
            return null;
        }
        return new PricePoint(timestamp, price);
    }

    /**
     * Um preço: número JSON (nunca texto), não negativo, com até
     * {@link #MAX_PRICE_SCALE} casas e até {@link #MAX_PRICE_INTEGER_DIGITS}
     * dígitos inteiros. Os dígitos inteiros são precision - scale, calculados em
     * long (com escala negativa, como 1E+2, a conta em int poderia estourar).
     * Devolve o valor exato, com os zeros à direita que vieram; ou null.
     */
    private static BigDecimal readPrice(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        BigDecimal price = node.decimalValue();
        long integerDigits = (long) price.precision() - price.scale();
        if (price.signum() < 0 || price.scale() > MAX_PRICE_SCALE || integerDigits > MAX_PRICE_INTEGER_DIGITS) {
            return null;
        }
        return price;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // devolve o pedido de interrupção a quem chamou
            log.warn("{}: chamada interrompida", CT18);
            throw new MarketDataUnavailableException(CT18);
        } catch (IOException e) {
            // Conexão recusada, host inexistente, tempo esgotado (HttpTimeoutException).
            log.warn("{}: falha de rede ({})", CT18, e.getClass().getSimpleName());
            throw new MarketDataUnavailableException(CT18);
        }
    }

    /**
     * Loga o status e o code devolvidos. SERVICE_KEY_INVALID (chaves diferentes nos
     * dois módulos) é erro de configuração: ERROR. Os demais, WARN.
     */
    private static void logUnexpectedStatus(int status, String code) {
        if ("SERVICE_KEY_INVALID".equals(code)) {
            log.error("{}: HTTP {} {}; a SERVICE_KEY difere da configurada no External Data Service",
                    CT18, status, code);
        } else {
            log.warn("{}: HTTP {} {}", CT18, status, code == null ? "sem code" : code);
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
