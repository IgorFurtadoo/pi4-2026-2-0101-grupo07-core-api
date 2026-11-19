package com.lemma.core.controller;

import com.lemma.core.TestApp;
import com.lemma.core.client.FakeExternalDataService;
import com.lemma.core.model.User;
import com.lemma.core.security.JwtService;
import com.lemma.core.service.FakeBlockchainClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static com.lemma.core.client.FakeExternalDataService.COUNTERPARTY;
import static com.lemma.core.client.FakeExternalDataService.hash;
import static com.lemma.core.client.FakeExternalDataService.historyBody;
import static com.lemma.core.client.FakeExternalDataService.historyItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /wallet/transactions (CT26) por HTTP real, com a mesma configuração do Main
 * (TestApp): a rota real, protegida pelo TokenFilter sem estar em PUBLIC_ROUTES, e
 * o External Data Service falso (servidor HTTP do JDK) atrás do AddressHistoryClient.
 * O usuário 1 (ana) está gravado.
 */
class WalletTransactionsControllerTest {

    private static final String ADDRESS = FakeBlockchainClient.address(1);
    private static final String CT24_PATH = "/addresses/" + ADDRESS + "/transactions";

    private static final String TOKEN_INVALID =
            "{\"code\":\"TOKEN_INVALID\",\"message\":\"Token ausente, inválido ou expirado.\"}";
    private static final String HISTORY_UNAVAILABLE = "{\"code\":\"HISTORY_UNAVAILABLE\","
            + "\"message\":\"Não foi possível obter o histórico agora. Tente novamente.\"}";

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start();
        app.repository.save(new User("ana@example.com", "hash-falso", ADDRESS, TestApp.NOW));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private String bearer(long userId) {
        return "Bearer " + app.jwtService.issue(userId).token();
    }

    private HttpResponse<String> get(String authorization) throws Exception {
        return get("/wallet/transactions", authorization, null);
    }

    private HttpResponse<String> get(String path, String authorization, String origin) throws Exception {
        HttpRequest.Builder request = app.request(path).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return app.send(request.build());
    }

    private void assertTokenInvalid(String authorization) throws Exception {
        HttpResponse<String> response = get(authorization);

        assertEquals(401, response.statusCode(), authorization);
        assertEquals(TOKEN_INVALID, response.body(), authorization);
    }

    // ---------- 200 ----------

    @Test
    void returns200WithExactBodyAndAmountAsPlainNumber() throws Exception {
        app.marketData.reply(200, historyBody(ADDRESS, "[" + historyItem(1, "0.000000000000000001") + ","
                + "{\"hash\":\"" + hash(2) + "\",\"direction\":\"SENT\",\"counterpartyAddress\":null,"
                + "\"amountEth\":\"123456789.123456789012345678\",\"timestamp\":\"2026-11-15T10:00:00.000000Z\","
                + "\"status\":\"FAILED\"}]"));

        HttpResponse<String> response = get(bearer(1));

        assertEquals(200, response.statusCode());
        assertEquals("{\"ethereumAddress\":\"" + ADDRESS + "\",\"network\":\"SEPOLIA\",\"transactions\":["
                + "{\"hash\":\"" + hash(1) + "\",\"direction\":\"RECEIVED\",\"counterpartyAddress\":\""
                + COUNTERPARTY + "\",\"amountEth\":0.000000000000000001,\"timestamp\":\"2026-11-16T04:12:00Z\","
                + "\"status\":\"CONFIRMED\"},"
                + "{\"hash\":\"" + hash(2) + "\",\"direction\":\"SENT\",\"counterpartyAddress\":null,"
                + "\"amountEth\":123456789.123456789012345678,\"timestamp\":\"2026-11-15T10:00:00Z\","
                + "\"status\":\"FAILED\"}]}", response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void emptyHistoryIs200WithAnEmptyList() throws Exception {
        app.marketData.reply(200, historyBody(ADDRESS, "[]"));

        HttpResponse<String> response = get(bearer(1));

        assertEquals(200, response.statusCode());
        assertEquals("{\"ethereumAddress\":\"" + ADDRESS + "\",\"network\":\"SEPOLIA\",\"transactions\":[]}",
                response.body());
    }

    @Test
    void ct24IsCalledWithTheTokenUsersAddressAndTheServiceKey() throws Exception {
        app.marketData.reply(200, historyBody(ADDRESS, "[]"));

        get(bearer(1));

        assertEquals(List.of(new FakeExternalDataService.Received("GET", CT24_PATH, null, true, 0)),
                app.marketData.requests());
    }

    @Test
    void addressInTheQueryStringIsIgnored() throws Exception {
        String other = FakeBlockchainClient.address(99);
        app.marketData.reply(200, historyBody(ADDRESS, "[]"));

        HttpResponse<String> response = get("/wallet/transactions?ethereumAddress=" + other + "&address=" + other,
                bearer(1), null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("{\"ethereumAddress\":\"" + ADDRESS + "\""));
        assertEquals(CT24_PATH, app.marketData.requests().get(0).rawPath());
    }

    // ---------- 401 TOKEN_INVALID ----------

    // A rota não está em Main.PUBLIC_ROUTES (MainRoutesTest): o 401 vem da proteção por padrão.

    @Test
    void withoutTokenWrongSchemeGarbageOrExpiredIs401() throws Exception {
        JwtService earlier = new JwtService(TestApp.JWT_SECRET, TestApp.JWT_TTL_MINUTES,
                Clock.fixed(TestApp.NOW.minus(Duration.ofHours(5)), ZoneOffset.UTC));

        assertTokenInvalid(null);
        assertTokenInvalid("Basic " + app.jwtService.issue(1).token());
        assertTokenInvalid("Bearer lixo");
        assertTokenInvalid("Bearer " + earlier.issue(1).token());
        assertTrue(app.marketData.requests().isEmpty());
    }

    @Test
    void validTokenOfAUserThatNoLongerExistsIs401WithoutCallingCt24() throws Exception {
        assertTokenInvalid(bearer(99));
        assertTrue(app.marketData.requests().isEmpty());
    }

    // ---------- 502 HISTORY_UNAVAILABLE ----------

    @Test
    void everyExternalDataServiceErrorIs502() throws Exception {
        for (int status : List.of(400, 401, 404, 429, 500, 502, 503)) {
            app.marketData.reply(status, "{\"code\":\"QUALQUER_CODE\",\"message\":\"x\"}");

            HttpResponse<String> response = get(bearer(1));

            assertEquals(502, response.statusCode(), String.valueOf(status));
            assertEquals(HISTORY_UNAVAILABLE, response.body(), String.valueOf(status));
        }
    }

    @Test
    void bodyOutOfFormatIs502AndNeverAPartialList() throws Exception {
        for (String body : List.of("", "isto não é JSON", "[]",
                historyBody(FakeBlockchainClient.address(99), "[]"),
                historyBody(ADDRESS, "[" + historyItem(1, "0.01") + "," + historyItem(2, "-1") + "]"),
                historyBody(ADDRESS, "[" + historyItem(1, "0.01") + ",{\"hash\":\"" + hash(2) + "\"}]"))) {
            app.marketData.reply(200, body);

            HttpResponse<String> response = get(bearer(1));

            assertEquals(502, response.statusCode(), body);
            assertEquals(HISTORY_UNAVAILABLE, response.body(), body);
        }
    }

    @Test
    void slowExternalDataServiceIs502() throws Exception {
        // O TestApp usa 2 s de tempo limite de resposta no AddressHistoryClient.
        app.marketData.reply(200, historyBody(ADDRESS, "[]")).delay(3_000);

        HttpResponse<String> response = get(bearer(1));

        assertEquals(502, response.statusCode());
        assertEquals(HISTORY_UNAVAILABLE, response.body());
    }

    // ---------- 405 e CORS ----------

    @Test
    void postIs405() throws Exception {
        HttpResponse<String> response = app.send(app.request("/wallet/transactions")
                .header("Authorization", bearer(1))
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertEquals(405, response.statusCode());
    }

    @Test
    void allowedOriginGetsTheCorsHeaderOn200On401AndOn502() throws Exception {
        app.marketData.reply(200, historyBody(ADDRESS, "[]"));
        HttpResponse<String> ok = get("/wallet/transactions", bearer(1), TestApp.CORS_ORIGIN);
        HttpResponse<String> unauthorized = get("/wallet/transactions", null, TestApp.CORS_ORIGIN);
        app.marketData.reply(502, "{\"code\":\"HISTORY_UNAVAILABLE\"}");
        HttpResponse<String> badGateway = get("/wallet/transactions", bearer(1), TestApp.CORS_ORIGIN);

        assertEquals(200, ok.statusCode());
        assertEquals(401, unauthorized.statusCode());
        assertEquals(502, badGateway.statusCode());
        for (HttpResponse<String> response : List.of(ok, unauthorized, badGateway)) {
            assertEquals(TestApp.CORS_ORIGIN,
                    response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        }
    }
}
