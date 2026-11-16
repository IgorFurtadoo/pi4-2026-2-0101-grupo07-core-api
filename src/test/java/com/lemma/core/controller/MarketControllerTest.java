package com.lemma.core.controller;

import com.lemma.core.TestApp;
import com.lemma.core.client.FakeExternalDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /market/{assetId} por HTTP real, com a mesma configuração do Main (TestApp):
 * a rota real, protegida pelo TokenFilter sem estar em PUBLIC_ROUTES, e o
 * External Data Service falso (servidor HTTP do JDK) atrás do MarketDataClient.
 */
class MarketControllerTest {

    private static final String TOKEN_INVALID =
            "{\"code\":\"TOKEN_INVALID\",\"message\":\"Token ausente, inválido ou expirado.\"}";
    private static final String ASSET_NOT_FOUND =
            "{\"code\":\"ASSET_NOT_FOUND\",\"message\":\"Ativo não encontrado.\"}";
    private static final String MARKET_DATA_UNAVAILABLE = "{\"code\":\"MARKET_DATA_UNAVAILABLE\","
            + "\"message\":\"Não foi possível obter os dados de mercado agora. Tente novamente.\"}";

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start();
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private String bearer() {
        return "Bearer " + app.jwtService.issue(1).token();
    }

    private HttpResponse<String> get(String assetId, String authorization) throws Exception {
        return get(assetId, authorization, null);
    }

    private HttpResponse<String> get(String assetId, String authorization, String origin) throws Exception {
        HttpRequest.Builder request = app.request("/market/" + assetId).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return app.send(request.build());
    }

    // ---------- 200 ----------

    @Test
    void returns200WithTheExactCt17Body() throws Exception {
        app.marketData.reply(200, FakeExternalDataService.body("ethereum", "USD", "3200.4500",
                "[{\"timestamp\":\"2026-10-19T12:00:00+01:00\",\"price\":3187.9},"
                        + "{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":0.000000000000000000000000000001},"
                        + "{\"timestamp\":\"2026-10-19T14:00:00.500Z\",\"price\":1E+2}]"));

        HttpResponse<String> response = get("ethereum", bearer());

        assertEquals(200, response.statusCode());
        assertEquals("{\"assetId\":\"ethereum\",\"currency\":\"USD\",\"currentPrice\":3200.4500,\"series\":["
                + "{\"timestamp\":\"2026-10-19T11:00:00Z\",\"price\":3187.9},"
                + "{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":0.000000000000000000000000000001},"
                + "{\"timestamp\":\"2026-10-19T14:00:00.500Z\",\"price\":100}]}", response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals(List.of(new FakeExternalDataService.Received("GET", "/market-data/ethereum", null, true, 0)),
                app.marketData.requests());
    }

    // ---------- 404 ASSET_NOT_FOUND ----------

    @Test
    void invalidAndUnknownAssetIdGetTheSame404() throws Exception {
        app.marketData.reply(404, FakeExternalDataService.ASSET_NOT_FOUND);

        for (String assetId : List.of("ETHEREUM", "a_b", "eth.ereum", "a".repeat(65))) {
            HttpResponse<String> response = get(assetId, bearer());

            assertEquals(404, response.statusCode(), assetId);
            assertEquals(ASSET_NOT_FOUND, response.body(), assetId);
        }
        assertTrue(app.marketData.requests().isEmpty(), "assetId inválido não chega ao External Data Service");

        HttpResponse<String> unknown = get("desconhecido", bearer());

        assertEquals(404, unknown.statusCode());
        assertEquals(ASSET_NOT_FOUND, unknown.body());
        assertEquals(1, app.marketData.requests().size());
    }

    // ---------- 502 ----------

    @Test
    void externalDataServiceFailureIs502() throws Exception {
        for (String body : List.of("{\"code\":\"INTERNAL_ERROR\"}", "isto não é JSON")) {
            app.marketData.reply(body.startsWith("{") ? 500 : 200, body);

            HttpResponse<String> response = get("ethereum", bearer());

            assertEquals(502, response.statusCode(), body);
            assertEquals(MARKET_DATA_UNAVAILABLE, response.body(), body);
        }
    }

    // ---------- 401 TOKEN_INVALID ----------

    // A rota não está em Main.PUBLIC_ROUTES (MainRoutesTest): o 401 vem da proteção por padrão.

    @Test
    void withoutTokenWrongSchemeOrGarbageIs401AndTheExternalDataServiceIsNotCalled() throws Exception {
        app.marketData.reply(200, FakeExternalDataService.validBody("ethereum"));

        for (String authorization : new String[] {null, "Basic " + app.jwtService.issue(1).token(), "Bearer lixo"}) {
            HttpResponse<String> response = get("ethereum", authorization);

            assertEquals(401, response.statusCode(), authorization);
            assertEquals(TOKEN_INVALID, response.body(), authorization);
        }
        assertTrue(app.marketData.requests().isEmpty());
    }

    @Test
    void theServiceKeyDoesNotOpenTheRoute() throws Exception {
        app.marketData.reply(200, FakeExternalDataService.validBody("ethereum"));

        HttpResponse<String> response = app.send(app.request("/market/ethereum")
                .header("X-Service-Key", TestApp.SERVICE_KEY).GET().build());

        assertEquals(401, response.statusCode());
        assertEquals(TOKEN_INVALID, response.body());
        assertTrue(app.marketData.requests().isEmpty());
    }

    // ---------- 405 ----------

    @Test
    void postIs405() throws Exception {
        HttpResponse<String> response = app.send(app.request("/market/ethereum")
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertEquals(405, response.statusCode());
        assertTrue(app.marketData.requests().isEmpty());
    }

    // ---------- CORS ----------

    @Test
    void allowedOriginGetsTheCorsHeaderOn200On404On502AndOn401() throws Exception {
        app.marketData.reply(200, FakeExternalDataService.validBody("ethereum"));
        HttpResponse<String> ok = get("ethereum", bearer(), TestApp.CORS_ORIGIN);
        HttpResponse<String> notFound = get("ETHEREUM", bearer(), TestApp.CORS_ORIGIN);
        HttpResponse<String> unauthorized = get("ethereum", null, TestApp.CORS_ORIGIN);
        app.marketData.reply(500, "{\"code\":\"INTERNAL_ERROR\"}");
        HttpResponse<String> badGateway = get("ethereum", bearer(), TestApp.CORS_ORIGIN);

        assertEquals(List.of(200, 404, 401, 502), List.of(ok.statusCode(), notFound.statusCode(),
                unauthorized.statusCode(), badGateway.statusCode()));
        for (HttpResponse<String> response : List.of(ok, notFound, unauthorized, badGateway)) {
            assertEquals(TestApp.CORS_ORIGIN,
                    response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        }
    }
}
