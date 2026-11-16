package com.lemma.core.service;

import com.lemma.core.LogCapture;
import com.lemma.core.TestClock;
import com.lemma.core.client.FakeExternalDataService;
import com.lemma.core.client.MarketDataClient;
import com.lemma.core.dto.MarketDataResponse;
import com.lemma.core.dto.PricePointResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.exception.RateLimitExceededException;
import com.lemma.core.ratelimit.SlidingWindowRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarketService com o MarketDataClient real apontando para o External Data Service
 * falso (servidor HTTP do JDK), sem rede externa. O limite usa um relógio de
 * teste, que só anda quando o teste manda.
 */
class MarketServiceTest {

    private static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";

    private static final Instant T0 = Instant.parse("2026-10-20T14:30:00Z");
    /** Limite pequeno dos testes de limite, numa janela de 60 s. */
    private static final int MAX = 3;

    private final FakeExternalDataService fake = new FakeExternalDataService(SERVICE_KEY);
    private final TestClock clock = new TestClock(T0);
    /** Limite alto: os testes que não são de limite não esbarram nele. */
    private final MarketService service = serviceWithLimit(1_000);
    private final MarketService limited = serviceWithLimit(MAX);

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private MarketService serviceWithLimit(int max) {
        return new MarketService(new MarketDataClient(fake.baseUrl(), SERVICE_KEY, Duration.ofMillis(300)),
                new SlidingWindowRateLimiter(max, Duration.ofSeconds(60), clock));
    }

    private static void assertApiError(MarketService target, int status, String code, String message,
                                       String assetId) {
        ApiException e = assertThrows(ApiException.class, () -> target.getMarketData(assetId), assetId);
        assertEquals(status, e.getStatus(), assetId);
        assertEquals(code, e.getCode(), assetId);
        assertEquals(message, e.getMessage(), assetId);
    }

    private void assertAssetNotFound(String assetId) {
        assertAssetNotFound(service, assetId);
    }

    private static void assertAssetNotFound(MarketService target, String assetId) {
        assertApiError(target, 404, "ASSET_NOT_FOUND", "Ativo não encontrado.", assetId);
    }

    private void assertUnavailable() {
        assertUnavailable(service);
    }

    private static void assertUnavailable(MarketService target) {
        assertApiError(target, 502, "MARKET_DATA_UNAVAILABLE",
                "Não foi possível obter os dados de mercado agora. Tente novamente.", "ethereum");
    }

    /** 429 RATE_LIMIT_EXCEEDED com o Retry-After exato. */
    private static void assertRateLimited(MarketService target, long retryAfterSeconds) {
        RateLimitExceededException e =
                assertThrows(RateLimitExceededException.class, () -> target.getMarketData("ethereum"));
        assertEquals(429, e.getStatus());
        assertEquals("RATE_LIMIT_EXCEEDED", e.getCode());
        assertEquals("Muitas requisições. Tente novamente em instantes.", e.getMessage());
        assertEquals(retryAfterSeconds, e.getRetryAfterSeconds());
    }

    private void acceptMax() {
        for (int i = 0; i < MAX; i++) {
            limited.getMarketData("ethereum");
        }
    }

    // ---------- sucesso ----------

    @Test
    void validDataBecomesTheCt17Response() {
        fake.reply(200, FakeExternalDataService.validBody("ethereum"));

        MarketDataResponse response = service.getMarketData("ethereum");

        assertEquals(new MarketDataResponse("ethereum", "USD", new BigDecimal("3200.45"), List.of(
                new PricePointResponse("2026-10-19T12:00:00Z", new BigDecimal("3187.9")),
                new PricePointResponse("2026-10-19T13:00:00Z", new BigDecimal("3190.12")),
                new PricePointResponse("2026-10-19T14:00:00Z", new BigDecimal("3201")))), response);
        assertEquals(List.of(new FakeExternalDataService.Received("GET", "/market-data/ethereum", null, true, 0)),
                fake.requests());
    }

    @Test
    void seriesKeepsTheReceivedOrderAndTimestampsComeOutCanonical() {
        fake.reply(200, FakeExternalDataService.body("ethereum", "USD", "3200.4500",
                "[{\"timestamp\":\"2026-10-19T14:00:00Z\",\"price\":3.10},"
                        + "{\"timestamp\":\"2026-10-19T12:00:00+01:00\",\"price\":1},"
                        + "{\"timestamp\":\"2026-10-19T13:00:00.500Z\",\"price\":2.000}]"));

        MarketDataResponse response = service.getMarketData("ethereum");

        assertEquals(new BigDecimal("3200.4500"), response.currentPrice());
        assertEquals(List.of(
                new PricePointResponse("2026-10-19T14:00:00Z", new BigDecimal("3.10")),
                new PricePointResponse("2026-10-19T11:00:00Z", new BigDecimal("1")),
                new PricePointResponse("2026-10-19T13:00:00.500Z", new BigDecimal("2.000"))), response.series());
    }

    // ---------- 404 ASSET_NOT_FOUND ----------

    @Test
    void assetIdOutOfFormatIs404WithoutCallingTheExternalDataService() {
        fake.reply(200, FakeExternalDataService.validBody("ethereum"));

        for (String assetId : Arrays.asList("ETHEREUM", "Ethereum", "a_b", "eth.ereum", "a".repeat(65), "",
                "a/b", "../ethereum", "eth%2Fereum", "eth\n", null)) {
            assertAssetNotFound(assetId);
        }

        assertTrue(fake.requests().isEmpty());
    }

    @Test
    void unknownAssetIsTheSame404() {
        fake.reply(404, FakeExternalDataService.ASSET_NOT_FOUND);

        assertAssetNotFound("desconhecido");
        assertEquals(1, fake.requests().size());
    }

    // ---------- 502 MARKET_DATA_UNAVAILABLE ----------

    @Test
    void anyOtherFailureIs502() {
        for (int status : List.of(401, 404, 429, 500, 502)) {
            fake.reply(status, "{\"code\":\"QUALQUER_CODE\"}");
            assertUnavailable();
        }
        fake.reply(200, "isto não é JSON");
        assertUnavailable();
        fake.reply(200, FakeExternalDataService.body("ethereum", "EUR", "1", FakeExternalDataService.VALID_SERIES));
        assertUnavailable();
        fake.reply(200, FakeExternalDataService.body("ethereum", "USD", "1", "[]"));
        assertUnavailable();
    }

    @Test
    void timeoutIs502() {
        fake.reply(200, FakeExternalDataService.validBody("ethereum")).delay(2_000);

        assertUnavailable();
    }

    @Test
    void connectionRefusedIs502() {
        fake.close();

        assertUnavailable();
    }

    // ---------- 429 RATE_LIMIT_EXCEEDED ----------

    @Test
    void refusedIs429WithTheExactRetryAfterAndTheFakeDoesNotSeeIt() {
        fake.reply(200, FakeExternalDataService.validBody("ethereum"));
        acceptMax();

        clock.advance(Duration.ofSeconds(10));
        assertRateLimited(limited, 50);
        clock.advance(Duration.ofSeconds(25));
        assertRateLimited(limited, 25);

        assertEquals(MAX, fake.requests().size(), "a recusada não chega ao External Data Service");
    }

    @Test
    void invalidAssetIdDoesNotConsumeTheLimit() {
        fake.reply(200, FakeExternalDataService.validBody("ethereum"));
        for (int i = 0; i < 2 * MAX; i++) {
            assertAssetNotFound(limited, "ETHEREUM");
        }

        acceptMax();                             // os inválidos não gastaram vaga
        assertRateLimited(limited, 60);

        assertAssetNotFound(limited, "ETHEREUM"); // esgotado o limite, o inválido ainda é 404
        assertEquals(MAX, fake.requests().size());
    }

    @Test
    void acceptedRequestCountsEvenWhenTheExternalDataServiceFails() {
        fake.reply(500, "{\"code\":\"INTERNAL_ERROR\"}");
        for (int i = 0; i < MAX; i++) {
            assertUnavailable(limited);
        }

        assertRateLimited(limited, 60);
        assertEquals(MAX, fake.requests().size());
    }

    @Test
    void afterTheWindowItPassesAgain() {
        fake.reply(200, FakeExternalDataService.validBody("ethereum"));
        acceptMax();
        clock.advance(Duration.ofSeconds(59));
        assertRateLimited(limited, 1);

        clock.advance(Duration.ofSeconds(1));
        acceptMax();
        assertRateLimited(limited, 60);
        assertEquals(2 * MAX, fake.requests().size());
    }

    // ---------- log ----------

    @Test
    void theServiceDoesNotLogOnItsOwn() {
        try (LogCapture logs = new LogCapture(MarketService.class);
             LogCapture limiterLogs = new LogCapture(SlidingWindowRateLimiter.class)) {
            fake.reply(200, FakeExternalDataService.validBody("ethereum"));
            service.getMarketData("ethereum");
            assertThrows(ApiException.class, () -> service.getMarketData("ETHEREUM"));
            fake.reply(404, FakeExternalDataService.ASSET_NOT_FOUND);
            assertThrows(ApiException.class, () -> service.getMarketData("desconhecido"));
            fake.reply(500, "{\"code\":\"INTERNAL_ERROR\"}");
            assertThrows(ApiException.class, () -> service.getMarketData("ethereum"));
            fake.reply(200, FakeExternalDataService.validBody("ethereum"));
            acceptMax();
            assertRateLimited(limited, 60);

            assertEquals("", logs.all());
            assertEquals("", limiterLogs.all());
        }
    }
}
