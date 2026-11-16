package com.lemma.core.client;

import com.lemma.core.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o CT18 fetchMarketData contra o External Data Service falso (servidor HTTP
 * do próprio JDK), sem rede externa: requisição, conferência do corpo 200,
 * tradução dos erros e o que vai ao log.
 */
class MarketDataClientTest {

    private static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";
    private static final String ASSET = "ethereum";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String REFUSED_LOG = "WARN CT18 fetchMarketData: HTTP 200 com corpo fora do formato";

    private FakeExternalDataService fake;

    @BeforeEach
    void startFake() {
        fake = new FakeExternalDataService(SERVICE_KEY);
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private MarketDataClient client() {
        return new MarketDataClient(fake.baseUrl(), SERVICE_KEY, TIMEOUT);
    }

    private MarketData fetch() {
        return client().fetchMarketData(ASSET);
    }

    /** O log nunca traz corpo, chave, URL, porta nem assetId. */
    private void assertLogClean(String log) {
        assertFalse(log.contains("mensagem-secreta-do-corpo"), log);
        assertFalse(log.contains(SERVICE_KEY), log);
        assertFalse(log.contains("127.0.0.1"), log);
        assertFalse(log.contains(fake.baseUrl().substring(fake.baseUrl().lastIndexOf(':') + 1)), log);
        assertFalse(log.contains("market-data"), log);
        assertFalse(log.contains(ASSET), log);
    }

    /** O 200 com este corpo é recusado, o log diz só "fora do formato" e houve uma requisição. */
    private void assertBodyRefused(String body) {
        fake.reply(200, body);
        int before = fake.requests().size();
        try (LogCapture logs = new LogCapture(MarketDataClient.class)) {
            assertThrows(MarketDataUnavailableException.class, this::fetch, body);
            assertEquals(REFUSED_LOG, logs.all(), body);
            assertLogClean(logs.all());
        }
        assertEquals(before + 1, fake.requests().size(), body);
    }

    private static String price(String priceJson) {
        return FakeExternalDataService.body(ASSET, "USD", priceJson, FakeExternalDataService.VALID_SERIES);
    }

    private static String series(String seriesJson) {
        return FakeExternalDataService.body(ASSET, "USD", "3200.45", seriesJson);
    }

    private static String point(String pointJson) {
        return series("[{\"timestamp\":\"2026-10-19T12:00:00Z\",\"price\":3187.9}," + pointJson + "]");
    }

    // ==================== requisição ====================

    @Test
    void isASingleGetToTheMarketDataPathWithServiceKeyAndNoBody() {
        fake.reply(200, FakeExternalDataService.validBody(ASSET));

        fetch();

        assertEquals(List.of(new FakeExternalDataService.Received("GET", "/market-data/ethereum", null, true, 0)),
                fake.requests());
    }

    @Test
    void theFakeNoticesAWrongServiceKey() {
        fake.reply(200, FakeExternalDataService.validBody(ASSET));

        new MarketDataClient(fake.baseUrl(), "outra-chave-falsa", TIMEOUT).fetchMarketData(ASSET);

        assertFalse(fake.requests().get(0).serviceKeyOk());
    }

    @Test
    void connectTimeoutIsThreeSeconds() {
        assertEquals(Duration.ofSeconds(3), MarketDataClient.CONNECT_TIMEOUT);
    }

    // ==================== 200 válido ====================

    @Test
    void validBodyComesBackExactlyAndInOrder() {
        fake.reply(200, FakeExternalDataService.validBody(ASSET));

        MarketData data = fetch();

        assertEquals(new MarketData(ASSET, "USD", new BigDecimal("3200.45"), List.of(
                new PricePoint(Instant.parse("2026-10-19T12:00:00Z"), new BigDecimal("3187.9")),
                new PricePoint(Instant.parse("2026-10-19T13:00:00Z"), new BigDecimal("3190.12")),
                new PricePoint(Instant.parse("2026-10-19T14:00:00Z"), new BigDecimal("3201")))), data);
    }

    @Test
    void pricesComeBackExactlyWithTrailingZeros() {
        for (String value : List.of("3200", "3200.45", "3200.4500", "0", "0.000",
                "0.000000000000000000000000000001",          // 30 casas
                "99999999999999999999.999999999999999999999999999999", // 20 dígitos inteiros e 30 casas
                "1E+2")) {
            fake.reply(200, FakeExternalDataService.body(ASSET, "USD", value,
                    "[{\"timestamp\":\"2026-10-19T12:00:00Z\",\"price\":" + value + "}]"));

            MarketData data = fetch();

            // BigDecimal.equals compara valor e escala: 3200.4500 não é 3200.45.
            assertEquals(new BigDecimal(value), data.currentPrice(), value);
            assertEquals(new BigDecimal(value), data.series().get(0).price(), value);
        }
    }

    @Test
    void seriesOfOnePointAndOfManyPointsKeepTheReceivedOrder() {
        fake.reply(200, series("[{\"timestamp\":\"2026-10-19T12:00:00Z\",\"price\":1}]"));
        assertEquals(List.of(new PricePoint(Instant.parse("2026-10-19T12:00:00Z"), new BigDecimal("1"))),
                fetch().series());

        // 200 pontos em ordem decrescente de tempo: a ordem recebida é mantida, sem ordenar.
        StringBuilder json = new StringBuilder("[");
        List<PricePoint> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Instant at = Instant.parse("2026-10-26T12:00:00Z").minus(Duration.ofMinutes(50L * i));
            json.append(i == 0 ? "" : ",").append("{\"timestamp\":\"").append(at).append("\",\"price\":")
                    .append(3000 + i).append(".5}");
            expected.add(new PricePoint(at, new BigDecimal((3000 + i) + ".5")));
        }
        fake.reply(200, series(json.append("]").toString()));

        assertEquals(expected, fetch().series());
    }

    @Test
    void timestampWithOffsetOrFractionIsReadAsTheSameInstant() {
        fake.reply(200, series("[{\"timestamp\":\"2026-10-19T12:00:00+01:00\",\"price\":1},"
                + "{\"timestamp\":\"2026-10-19T12:00:00.500Z\",\"price\":2}]"));

        List<PricePoint> points = fetch().series();

        assertEquals(Instant.parse("2026-10-19T11:00:00Z"), points.get(0).timestamp());
        assertEquals(Instant.parse("2026-10-19T12:00:00.500Z"), points.get(1).timestamp());
    }

    @Test
    void extraFieldsAreIgnored() {
        fake.reply(200, "{\"assetId\":\"ethereum\",\"currency\":\"USD\",\"currentPrice\":3200.45,\"fonte\":\"x\","
                + "\"series\":[{\"timestamp\":\"2026-10-19T12:00:00Z\",\"price\":3187.9,\"volume\":1}]}");

        assertEquals(new BigDecimal("3200.45"), fetch().currentPrice());
    }

    @Test
    void successIsNotLogged() {
        fake.reply(200, FakeExternalDataService.validBody(ASSET));

        try (LogCapture logs = new LogCapture(MarketDataClient.class)) {
            fetch();
            assertEquals("", logs.all());
        }
    }

    // ==================== assetId fora do formato: nenhuma chamada ====================

    @Test
    void assetIdOutOfFormatMakesNoCall() {
        fake.reply(200, FakeExternalDataService.validBody(ASSET));
        MarketDataClient client = client();

        for (String assetId : Arrays.asList(null, "", "ETHEREUM", "Ethereum", "a_b", "eth.ereum", "a".repeat(65),
                "../x", "a/b", "a?b", "a%2Fb", "eth\n", "eth ereum", "étheréum")) {
            try (LogCapture logs = new LogCapture(MarketDataClient.class)) {
                assertThrows(MarketDataUnavailableException.class, () -> client.fetchMarketData(assetId), assetId);
                assertEquals("ERROR CT18 fetchMarketData: assetId fora do formato; a chamada não foi feita",
                        logs.all(), assetId);
            }
        }

        assertTrue(fake.requests().isEmpty());
    }

    @Test
    void assetIdAtTheLimitsIsAccepted() {
        for (String assetId : List.of("a", "0", "-", "a".repeat(64), "usd-coin", "wrapped-bitcoin-2")) {
            fake.reply(200, FakeExternalDataService.validBody(assetId));

            assertEquals(assetId, client().fetchMarketData(assetId).assetId());
        }
    }

    // ==================== 200 fora do formato ====================

    @Test
    void assetIdDifferentMissingOrNotTextIsRefused() {
        assertBodyRefused(FakeExternalDataService.validBody("bitcoin"));
        assertBodyRefused(FakeExternalDataService.validBody("ETHEREUM"));
        assertBodyRefused("{\"currency\":\"USD\",\"currentPrice\":1,\"series\":"
                + FakeExternalDataService.VALID_SERIES + "}");
        assertBodyRefused("{\"assetId\":null,\"currency\":\"USD\",\"currentPrice\":1,\"series\":"
                + FakeExternalDataService.VALID_SERIES + "}");
        assertBodyRefused("{\"assetId\":1,\"currency\":\"USD\",\"currentPrice\":1,\"series\":"
                + FakeExternalDataService.VALID_SERIES + "}");
        assertBodyRefused("{\"assetId\":[\"ethereum\"],\"currency\":\"USD\",\"currentPrice\":1,\"series\":"
                + FakeExternalDataService.VALID_SERIES + "}");
    }

    @Test
    void currencyNotExactlyUsdOrMissingIsRefused() {
        assertBodyRefused(FakeExternalDataService.body(ASSET, "EUR", "1", FakeExternalDataService.VALID_SERIES));
        assertBodyRefused(FakeExternalDataService.body(ASSET, "usd", "1", FakeExternalDataService.VALID_SERIES));
        assertBodyRefused(FakeExternalDataService.body(ASSET, "USD ", "1", FakeExternalDataService.VALID_SERIES));
        assertBodyRefused("{\"assetId\":\"ethereum\",\"currentPrice\":1,\"series\":"
                + FakeExternalDataService.VALID_SERIES + "}");
        assertBodyRefused("{\"assetId\":\"ethereum\",\"currency\":null,\"currentPrice\":1,\"series\":"
                + FakeExternalDataService.VALID_SERIES + "}");
    }

    @Test
    void currentPriceTextMissingOrNegativeIsRefused() {
        assertBodyRefused(price("\"3200.45\""));
        assertBodyRefused(price("null"));
        assertBodyRefused(price("true"));
        assertBodyRefused(price("[3200.45]"));
        assertBodyRefused(price("{\"valor\":1}"));
        assertBodyRefused(price("-1"));
        assertBodyRefused(price("-0.01"));
        assertBodyRefused("{\"assetId\":\"ethereum\",\"currency\":\"USD\",\"series\":"
                + FakeExternalDataService.VALID_SERIES + "}");
    }

    @Test
    void currentPriceOutsideTheNumericLimitsIsRefused() {
        assertBodyRefused(price("1E+999999999"));                             // não seria serializável em notação plana
        assertBodyRefused(price("1E-999999999"));
        assertBodyRefused(price("0.0000000000000000000000000000001"));        // 31 casas
        assertBodyRefused(price("1E-31"));
        assertBodyRefused(price("1.0000000000000000000000000000000"));        // 31 casas, mesmo com zeros
        assertBodyRefused(price("123456789012345678901"));                    // 21 dígitos inteiros
        assertBodyRefused(price("100000000000000000000.5"));
        assertBodyRefused(price("1E+20"));                                    // 21 dígitos inteiros
    }

    @Test
    void seriesMissingNotAListOrEmptyIsRefused() {
        assertBodyRefused("{\"assetId\":\"ethereum\",\"currency\":\"USD\",\"currentPrice\":1}");
        assertBodyRefused(series("null"));
        assertBodyRefused(series("{}"));
        assertBodyRefused(series("\"pontos\""));
        assertBodyRefused(series("1"));
        assertBodyRefused(series("[]"));
    }

    @Test
    void anInvalidPointInvalidatesTheWholeBody() {
        assertBodyRefused(point("1"));
        assertBodyRefused(point("null"));
        assertBodyRefused(point("\"x\""));
        assertBodyRefused(point("[\"2026-10-19T13:00:00Z\",1]"));
        // timestamp
        assertBodyRefused(point("{\"price\":1}"));
        assertBodyRefused(point("{\"timestamp\":null,\"price\":1}"));
        assertBodyRefused(point("{\"timestamp\":1760875200,\"price\":1}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19\",\"price\":1}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00\",\"price\":1}"));   // sem zona
        assertBodyRefused(point("{\"timestamp\":\"2026-13-01T00:00:00Z\",\"price\":1}"));
        assertBodyRefused(point("{\"timestamp\":\"ontem\",\"price\":1}"));
        // price
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00Z\"}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":null}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":\"3190.12\"}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":-1}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":1E+999999999}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":1E-31}"));
        assertBodyRefused(point("{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":123456789012345678901}"));
    }

    @Test
    void rootThatIsNotAnObjectEmptyOrUnreadableIsRefused() {
        assertBodyRefused("");
        assertBodyRefused("[]");
        assertBodyRefused("123");
        assertBodyRefused("\"x\"");
        assertBodyRefused("null");
        assertBodyRefused("isto não é JSON mensagem-secreta-do-corpo");
        assertBodyRefused("{\"assetId\":\"ethereum\",");
    }

    // ==================== status de erro ====================

    @Test
    void assetNotFoundIsItsOwnExceptionWithoutLog() {
        fake.reply(404, FakeExternalDataService.ASSET_NOT_FOUND);

        try (LogCapture logs = new LogCapture(MarketDataClient.class)) {
            AssetNotFoundException e = assertThrows(AssetNotFoundException.class, this::fetch);
            assertEquals("", logs.all());
            assertFalse(e.getMessage().contains(ASSET));
        }
        assertEquals(1, fake.requests().size());
    }

    @Test
    void errorStatusesAreUnavailableAndOnlyTheCodeIsLogged() {
        record Case(int status, String body, String expectedLog) {
        }
        String secret = ",\"message\":\"mensagem-secreta-do-corpo\"}";
        List<Case> cases = List.of(
                new Case(404, "{\"code\":\"NOT_FOUND\"" + secret, "WARN CT18 fetchMarketData: HTTP 404 NOT_FOUND"),
                new Case(404, "", "WARN CT18 fetchMarketData: HTTP 404 sem code"),
                new Case(404, "<html>mensagem-secreta-do-corpo</html>", "WARN CT18 fetchMarketData: HTTP 404 sem code"),
                new Case(401, "{\"code\":\"SERVICE_KEY_INVALID\"" + secret, "ERROR CT18 fetchMarketData: HTTP 401"
                        + " SERVICE_KEY_INVALID; a SERVICE_KEY difere da configurada no External Data Service"),
                new Case(429, "{\"code\":\"RATE_LIMIT_EXCEEDED\"" + secret,
                        "WARN CT18 fetchMarketData: HTTP 429 RATE_LIMIT_EXCEEDED"),
                new Case(500, "{\"code\":\"INTERNAL_ERROR\"" + secret,
                        "WARN CT18 fetchMarketData: HTTP 500 INTERNAL_ERROR"),
                new Case(502, "{\"code\":\"MARKET_DATA_UNAVAILABLE\"" + secret,
                        "WARN CT18 fetchMarketData: HTTP 502 MARKET_DATA_UNAVAILABLE"),
                new Case(500, "{\"code\":\"código fora do formato\"" + secret,
                        "WARN CT18 fetchMarketData: HTTP 500 sem code"),
                new Case(400, "{\"code\":\"ASSET_NOT_FOUND\"" + secret,
                        "WARN CT18 fetchMarketData: HTTP 400 ASSET_NOT_FOUND"),
                new Case(201, FakeExternalDataService.validBody(ASSET), "WARN CT18 fetchMarketData: HTTP 201 sem code"));

        for (Case c : cases) {
            fake.reply(c.status(), c.body());
            try (LogCapture logs = new LogCapture(MarketDataClient.class)) {
                assertThrows(MarketDataUnavailableException.class, this::fetch, c.expectedLog());
                assertEquals(c.expectedLog(), logs.all());
                assertLogClean(logs.all());
            }
        }
        assertEquals(cases.size(), fake.requests().size());
    }

    @Test
    void serverErrorIsNotRetried() throws Exception {
        fake.reply(500, "{\"code\":\"INTERNAL_ERROR\"}");

        assertThrows(MarketDataUnavailableException.class, this::fetch);
        Thread.sleep(200); // dá tempo de uma segunda requisição chegar, se houvesse

        assertEquals(1, fake.requests().size());
    }

    // ==================== rede ====================

    @Test
    void slowerThanTheResponseTimeoutFailsInTime() {
        fake.reply(200, FakeExternalDataService.validBody(ASSET)).delay(2_000);
        MarketDataClient client = new MarketDataClient(fake.baseUrl(), SERVICE_KEY, Duration.ofMillis(300));

        try (LogCapture logs = new LogCapture(MarketDataClient.class)) {
            assertTimeoutPreemptively(Duration.ofMillis(1_500),
                    () -> assertThrows(MarketDataUnavailableException.class, () -> client.fetchMarketData(ASSET)));
            assertEquals("WARN CT18 fetchMarketData: falha de rede (HttpTimeoutException)", logs.all());
            assertLogClean(logs.all());
        }
        assertEquals(1, fake.requests().size());
    }

    @Test
    void connectionRefusedFails() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort(); // ao sair do try, a porta fica fechada
        }
        MarketDataClient client = new MarketDataClient("http://127.0.0.1:" + closedPort, SERVICE_KEY, TIMEOUT);

        try (LogCapture logs = new LogCapture(MarketDataClient.class)) {
            assertThrows(MarketDataUnavailableException.class, () -> client.fetchMarketData(ASSET));
            assertEquals("WARN CT18 fetchMarketData: falha de rede (ConnectException)", logs.all());
            assertFalse(logs.all().contains(String.valueOf(closedPort)));
        }
    }

    @Test
    void exceptionMessagesCarryNoUrlKeyOrAssetId() {
        fake.reply(500, "{\"code\":\"INTERNAL_ERROR\"}");

        MarketDataUnavailableException e = assertThrows(MarketDataUnavailableException.class, this::fetch);

        assertEquals("CT18 fetchMarketData falhou", e.getMessage());
        assertNull(e.getCause());
    }
}
