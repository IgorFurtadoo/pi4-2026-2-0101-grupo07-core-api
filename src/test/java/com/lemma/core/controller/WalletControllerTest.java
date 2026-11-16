package com.lemma.core.controller;

import com.lemma.core.TestApp;
import com.lemma.core.model.User;
import com.lemma.core.security.JwtService;
import com.lemma.core.service.FakeBlockchainClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /wallet/balance por HTTP real, com a mesma configuração do Main (TestApp):
 * a rota real, protegida pelo TokenFilter sem estar em PUBLIC_ROUTES. O usuário 1
 * (ana) está gravado; o Blockchain Service é o falso.
 */
class WalletControllerTest {

    private static final String ADDRESS = FakeBlockchainClient.address(1);

    private static final String TOKEN_INVALID =
            "{\"code\":\"TOKEN_INVALID\",\"message\":\"Token ausente, inválido ou expirado.\"}";
    private static final String BLOCKCHAIN_UNAVAILABLE = "{\"code\":\"BLOCKCHAIN_UNAVAILABLE\","
            + "\"message\":\"Não foi possível consultar o saldo agora. Tente novamente.\"}";

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

    private String validToken(long userId) {
        return app.jwtService.issue(userId).token();
    }

    private HttpResponse<String> getBalance(String authorization) throws Exception {
        return getBalance(authorization, null);
    }

    private HttpResponse<String> getBalance(String authorization, String origin) throws Exception {
        HttpRequest.Builder request = app.request("/wallet/balance").GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return app.send(request.build());
    }

    private void assertTokenInvalid(String authorization) throws Exception {
        HttpResponse<String> response = getBalance(authorization);

        assertEquals(401, response.statusCode(), authorization);
        assertEquals(TOKEN_INVALID, response.body(), authorization);
    }

    // ---------- 200 ----------

    @Test
    void returns200WithExactBodyAndBalanceAsPlainNumber() throws Exception {
        app.blockchain.balances.put(ADDRESS, new BigDecimal("0.000000000000000001"));

        HttpResponse<String> response = getBalance("Bearer " + validToken(1));

        assertEquals(200, response.statusCode());
        assertEquals("{\"ethereumAddress\":\"" + ADDRESS + "\",\"balanceEth\":0.000000000000000001,"
                + "\"network\":\"SEPOLIA\"}", response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void otherValuesAreWrittenExactly() throws Exception {
        for (String value : List.of("0.25", "0", "0.000000000000000000", "123456789.123456789012345678")) {
            app.blockchain.balances.put(ADDRESS, new BigDecimal(value));

            HttpResponse<String> response = getBalance("Bearer " + validToken(1));

            assertEquals("{\"ethereumAddress\":\"" + ADDRESS + "\",\"balanceEth\":" + value
                    + ",\"network\":\"SEPOLIA\"}", response.body(), value);
        }
    }

    @Test
    void theAddressQueriedIsTheOneOfTheTokenUser() throws Exception {
        getBalance("Bearer " + validToken(1));

        assertEquals(List.of(ADDRESS), app.blockchain.balanceQueries);
    }

    @Test
    void addressInTheQueryStringIsIgnored() throws Exception {
        String other = FakeBlockchainClient.address(99);

        HttpResponse<String> response = app.send(app.request("/wallet/balance?ethereumAddress=" + other)
                .header("Authorization", "Bearer " + validToken(1)).GET().build());

        assertEquals(200, response.statusCode());
        assertEquals(List.of(ADDRESS), app.blockchain.balanceQueries);
    }

    // ---------- 401 TOKEN_INVALID ----------

    // A rota não está em Main.PUBLIC_ROUTES (MainRoutesTest): o 401 vem da proteção por padrão.

    @Test
    void withoutTokenWrongSchemeGarbageOrExpiredIs401() throws Exception {
        JwtService earlier = new JwtService(TestApp.JWT_SECRET, TestApp.JWT_TTL_MINUTES,
                Clock.fixed(TestApp.NOW.minus(Duration.ofHours(5)), ZoneOffset.UTC));

        assertTokenInvalid(null);
        assertTokenInvalid("Basic " + validToken(1));
        assertTokenInvalid("Bearer lixo");
        assertTokenInvalid("Bearer " + earlier.issue(1).token());
        assertTrue(app.blockchain.balanceQueries.isEmpty());
    }

    @Test
    void validTokenOfAUserThatNoLongerExistsIs401() throws Exception {
        assertTokenInvalid("Bearer " + validToken(99));
        assertTrue(app.blockchain.balanceQueries.isEmpty());
    }

    // ---------- 502 e 405 ----------

    @Test
    void blockchainServiceFailureIs502() throws Exception {
        app.blockchain.failGetBalance = true;

        HttpResponse<String> response = getBalance("Bearer " + validToken(1));

        assertEquals(502, response.statusCode());
        assertEquals(BLOCKCHAIN_UNAVAILABLE, response.body());
    }

    @Test
    void postIs405() throws Exception {
        HttpResponse<String> response = app.send(app.request("/wallet/balance")
                .header("Authorization", "Bearer " + validToken(1))
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertEquals(405, response.statusCode());
    }

    // ---------- CORS ----------

    @Test
    void allowedOriginGetsTheCorsHeaderOn200On401AndOn502() throws Exception {
        HttpResponse<String> ok = getBalance("Bearer " + validToken(1), TestApp.CORS_ORIGIN);
        HttpResponse<String> unauthorized = getBalance(null, TestApp.CORS_ORIGIN);
        app.blockchain.failGetBalance = true;
        HttpResponse<String> badGateway = getBalance("Bearer " + validToken(1), TestApp.CORS_ORIGIN);

        assertEquals(200, ok.statusCode());
        assertEquals(401, unauthorized.statusCode());
        assertEquals(502, badGateway.statusCode());
        for (HttpResponse<String> response : List.of(ok, unauthorized, badGateway)) {
            assertEquals(TestApp.CORS_ORIGIN,
                    response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        }
    }
}
