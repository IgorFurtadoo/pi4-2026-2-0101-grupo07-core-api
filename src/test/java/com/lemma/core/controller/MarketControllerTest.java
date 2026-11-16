package com.lemma.core.controller;

import com.lemma.core.TestApp;
import com.lemma.core.client.FakeExternalDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /market/{assetId} por HTTP real, com a mesma configuração do Main (TestApp):
 * a rota real, protegida pelo TokenFilter sem estar em PUBLIC_ROUTES, e o
 * External Data Service falso (servidor HTTP do JDK) atrás do MarketDataClient.
 *
 * Os testes de limite sobem um segundo TestApp com limite pequeno (janela de 60 s)
 * e andam com o relógio do limite (marketClock), sem Thread.sleep.
 */
class MarketControllerTest {

    private static final String TOKEN_INVALID =
            "{\"code\":\"TOKEN_INVALID\",\"message\":\"Token ausente, inválido ou expirado.\"}";
    private static final String ASSET_NOT_FOUND =
            "{\"code\":\"ASSET_NOT_FOUND\",\"message\":\"Ativo não encontrado.\"}";
    private static final String MARKET_DATA_UNAVAILABLE = "{\"code\":\"MARKET_DATA_UNAVAILABLE\","
            + "\"message\":\"Não foi possível obter os dados de mercado agora. Tente novamente.\"}";

    private static final String RATE_LIMIT_EXCEEDED = "{\"code\":\"RATE_LIMIT_EXCEEDED\","
            + "\"message\":\"Muitas requisições. Tente novamente em instantes.\"}";
    /** Limite pequeno dos testes de limite. */
    private static final int MAX = 3;

    private final TestApp app = new TestApp();
    /** TestApp com limite pequeno, só nos testes de limite; parado no fim do teste. */
    private TestApp limited;

    @BeforeEach
    void startServer() {
        app.start();
    }

    @AfterEach
    void stopServer() {
        app.stop();
        if (limited != null) {
            limited.stop();
        }
    }

    private String bearer() {
        return bearer(app, 1);
    }

    private static String bearer(TestApp target, long userId) {
        return "Bearer " + target.jwtService.issue(userId).token();
    }

    /** Sobe o TestApp com limite de max por 60 s e o falso respondendo 200. */
    private TestApp startLimited(int max) {
        limited = new TestApp(max, Duration.ofSeconds(60)).start();
        limited.marketData.reply(200, FakeExternalDataService.validBody("ethereum"));
        return limited;
    }

    private HttpResponse<String> get(String assetId, String authorization) throws Exception {
        return get(app, assetId, authorization, null);
    }

    private HttpResponse<String> get(String assetId, String authorization, String origin) throws Exception {
        return get(app, assetId, authorization, origin);
    }

    private static HttpResponse<String> get(TestApp target, String assetId, String authorization, String origin)
            throws Exception {
        HttpRequest.Builder request = target.request("/market/" + assetId).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return target.send(request.build());
    }

    /** 200 de GET /market/ethereum no TestApp limitado, com o token do usuário. */
    private void assertAccepted(long userId) throws Exception {
        HttpResponse<String> response = get(limited, "ethereum", bearer(limited, userId), null);
        assertEquals(200, response.statusCode(), response.body());
    }

    /** 429 com corpo exato e Retry-After exato (uma linha só, inteiro). */
    private static void assertRateLimited(HttpResponse<String> response, long retryAfterSeconds) {
        assertEquals(429, response.statusCode(), response.body());
        assertEquals(RATE_LIMIT_EXCEEDED, response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals(List.of(String.valueOf(retryAfterSeconds)), response.headers().allValues("Retry-After"));
    }

    /** Nenhum Access-Control-* na resposta. */
    private static void assertNoCorsHeaders(HttpResponse<String> response, String origin) {
        List<String> corsHeaders = response.headers().map().keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("access-control-"))
                .toList();
        assertEquals(List.of(), corsHeaders, origin);
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

    // ---------- 429 RATE_LIMIT_EXCEEDED ----------

    @Test
    void beyondTheLimitIs429WithExactBodyAndRetryAfterAndTheFakeSeesOnlyMax() throws Exception {
        startLimited(MAX);
        for (int i = 0; i < MAX; i++) {
            assertAccepted(1);
        }
        limited.marketClock.advance(Duration.ofSeconds(10));

        assertRateLimited(get(limited, "ethereum", bearer(limited, 1), null), 50);
        assertEquals(MAX, limited.marketData.requests().size(), "a recusada não chega ao External Data Service");
    }

    @Test
    void requestsRefusedBeforeTheLimitDoNotConsumeIt() throws Exception {
        startLimited(MAX);
        String basic = "Basic " + limited.jwtService.issue(1).token();
        List<String> badAuthorizations = Arrays.asList(null, basic, "Bearer lixo");

        for (int i = 0; i < MAX; i++) {
            for (String authorization : badAuthorizations) {
                HttpResponse<String> response = get(limited, "ethereum", authorization, null);
                assertEquals(401, response.statusCode(), authorization);
                assertEquals(TOKEN_INVALID, response.body(), authorization);
            }
            HttpResponse<String> invalid = get(limited, "ETHEREUM", bearer(limited, 1), null);
            assertEquals(404, invalid.statusCode());
            assertEquals(ASSET_NOT_FOUND, invalid.body());
        }

        // Nenhuma das anteriores gastou vaga.
        for (int i = 0; i < MAX; i++) {
            assertAccepted(1);
        }
        assertRateLimited(get(limited, "ethereum", bearer(limited, 1), null), 60);

        // Esgotado o limite, 401 e 404 continuam iguais (o token e o assetId vêm antes do limite).
        for (String authorization : badAuthorizations) {
            HttpResponse<String> response = get(limited, "ethereum", authorization, null);
            assertEquals(401, response.statusCode(), authorization);
            assertEquals(TOKEN_INVALID, response.body(), authorization);
        }
        HttpResponse<String> invalid = get(limited, "ETHEREUM", bearer(limited, 1), null);
        assertEquals(404, invalid.statusCode());
        assertEquals(ASSET_NOT_FOUND, invalid.body());
        assertEquals(MAX, limited.marketData.requests().size());
    }

    @Test
    void twoUsersShareTheLimitAndItReopensAfterTheWindow() throws Exception {
        startLimited(MAX);
        for (int i = 0; i < MAX; i++) {
            assertAccepted(1);
        }
        limited.marketClock.advance(Duration.ofSeconds(45));

        assertRateLimited(get(limited, "ethereum", bearer(limited, 2), null), 15);

        limited.marketClock.advance(Duration.ofSeconds(15));
        assertAccepted(2);
        assertEquals(MAX + 1, limited.marketData.requests().size());
    }

    @Test
    void the429CarriesCorsHeadersOnlyForTheAllowedOrigin() throws Exception {
        startLimited(MAX);
        for (int i = 0; i < MAX; i++) {
            assertAccepted(1);
        }

        HttpResponse<String> allowed = get(limited, "ethereum", bearer(limited, 1), TestApp.CORS_ORIGIN);
        HttpResponse<String> otherOrigin = get(limited, "ethereum", bearer(limited, 1), "http://evil.example");
        HttpResponse<String> withoutOrigin = get(limited, "ethereum", bearer(limited, 1), null);

        assertRateLimited(allowed, 60);
        assertEquals(TestApp.CORS_ORIGIN, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals(List.of("Retry-After"), allowed.headers().allValues("Access-Control-Expose-Headers"));
        assertRateLimited(otherOrigin, 60);
        assertNoCorsHeaders(otherOrigin, "http://evil.example");
        assertRateLimited(withoutOrigin, 60);
        assertNoCorsHeaders(withoutOrigin, "sem Origin");
    }

    @Test
    void simultaneousBurstLetsExactlyMaxThrough() throws Exception {
        int max = 10;
        int requests = 30;
        startLimited(max);
        String authorization = bearer(limited, 1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch ready = new CountDownLatch(requests);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("a largada não veio em 10 s");
                    }
                    return get(limited, "ethereum", authorization, null);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "as threads não ficaram prontas em 10 s");
            start.countDown();

            int ok = 0;
            int tooMany = 0;
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> response = result.get(10, TimeUnit.SECONDS);
                if (response.statusCode() == 200) {
                    ok++;
                } else {
                    assertRateLimited(response, 60);
                    tooMany++;
                }
            }
            assertEquals(max, ok, "200");
            assertEquals(requests - max, tooMany, "429");
            assertEquals(max, limited.marketData.requests().size(), "o falso vê exatamente o limite");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "as threads não terminaram em 10 s");
        }
    }
}
