package com.lemma.core.service;

import com.lemma.core.LogCapture;
import com.lemma.core.client.FakeExternalDataService;
import com.lemma.core.client.MarketDataClient;
import com.lemma.core.dto.MarketDataResponse;
import com.lemma.core.dto.PricePointResponse;
import com.lemma.core.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarketService com o MarketDataClient real apontando para o External Data Service
 * falso (servidor HTTP do JDK), sem rede externa.
 */
class MarketServiceTest {

    private static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";

    private final FakeExternalDataService fake = new FakeExternalDataService(SERVICE_KEY);
    private final MarketService service =
            new MarketService(new MarketDataClient(fake.baseUrl(), SERVICE_KEY, Duration.ofMillis(300)));

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private void assertApiError(int status, String code, String message, String assetId) {
        ApiException e = assertThrows(ApiException.class, () -> service.getMarketData(assetId), assetId);
        assertEquals(status, e.getStatus(), assetId);
        assertEquals(code, e.getCode(), assetId);
        assertEquals(message, e.getMessage(), assetId);
    }

    private void assertAssetNotFound(String assetId) {
        assertApiError(404, "ASSET_NOT_FOUND", "Ativo não encontrado.", assetId);
    }

    private void assertUnavailable() {
        assertApiError(502, "MARKET_DATA_UNAVAILABLE",
                "Não foi possível obter os dados de mercado agora. Tente novamente.", "ethereum");
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

    // ---------- log ----------

    @Test
    void theServiceDoesNotLogOnItsOwn() {
        try (LogCapture logs = new LogCapture(MarketService.class)) {
            fake.reply(200, FakeExternalDataService.validBody("ethereum"));
            service.getMarketData("ethereum");
            assertThrows(ApiException.class, () -> service.getMarketData("ETHEREUM"));
            fake.reply(404, FakeExternalDataService.ASSET_NOT_FOUND);
            assertThrows(ApiException.class, () -> service.getMarketData("desconhecido"));
            fake.reply(500, "{\"code\":\"INTERNAL_ERROR\"}");
            assertThrows(ApiException.class, () -> service.getMarketData("ethereum"));

            assertEquals("", logs.all());
        }
    }
}
