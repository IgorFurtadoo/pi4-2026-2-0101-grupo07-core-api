package com.lemma.core.security;

import com.lemma.core.TestApp;
import com.lemma.core.model.User;
import com.lemma.core.service.FakeBlockchainClient;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS por HTTP real, com a configuração do Main (TestApp) e uma rota protegida
 * que só existe neste teste. A origem permitida é TestApp.CORS_ORIGIN.
 */
class CorsTest {

    private static final String ORIGIN = TestApp.CORS_ORIGIN;
    private static final String LOGIN_BODY = "{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}";

    /** Parecidas com a permitida, mas diferentes: nenhuma pode receber permissão. */
    private static final List<String> NOT_ALLOWED = List.of(
            "null",
            "http://localhost:5173/",
            "http://localhost:5173.evil.com",
            "HTTP://LOCALHOST:5173",
            "https://localhost:5173",
            "http://localhost:5174",
            "http://evil.example");

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start(javalin -> javalin.routes.get("/test/protected",
                ctx -> ctx.result("userId=" + TokenFilter.userId(ctx))));
        app.repository.save(new User("ana@example.com", app.hasher.hash("Senha@12345"),
                FakeBlockchainClient.address(1), TestApp.NOW));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    /** Pré-requisição como o navegador manda: OPTIONS, sem Authorization. */
    private HttpResponse<String> preflight(String path, String origin, String method) throws Exception {
        return app.send(app.request(path)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private HttpResponse<String> loginFrom(String origin) throws Exception {
        return app.send(app.request("/auth/login")
                .header("Origin", origin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(LOGIN_BODY))
                .build());
    }

    private static Optional<String> allowOrigin(HttpResponse<String> response) {
        return response.headers().firstValue("Access-Control-Allow-Origin");
    }

    private static List<String> exposeHeaders(HttpResponse<String> response) {
        return response.headers().allValues("Access-Control-Expose-Headers");
    }

    /** Nenhum Access-Control-* na resposta (nem Allow-*, nem Expose-Headers). */
    private static void assertNoCorsPermission(HttpResponse<String> response, String origin) {
        List<String> corsHeaders = response.headers().map().keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("access-control-"))
                .toList();
        assertEquals(List.of(), corsHeaders, origin);
    }

    /** Valores do Vary, juntando linhas repetidas e listas separadas por vírgula. */
    private static List<String> varyValues(HttpHeaders headers) {
        return headers.allValues("Vary").stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .toList();
    }

    // ---------- pré-requisição ----------

    @Test
    void preflightFromTheAllowedOriginIsAcceptedWithFixedLists() throws Exception {
        HttpResponse<String> response = preflight("/auth/login", ORIGIN, "POST");

        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
        assertEquals(Optional.of(ORIGIN), allowOrigin(response));
        assertEquals(Optional.of("GET, POST, OPTIONS"),
                response.headers().firstValue("Access-Control-Allow-Methods"));
        assertEquals(Optional.of("Authorization, Content-Type"),
                response.headers().firstValue("Access-Control-Allow-Headers"));
        assertEquals(Optional.empty(), response.headers().firstValue("Access-Control-Allow-Credentials"));
        assertEquals(Optional.empty(), response.headers().firstValue("Access-Control-Max-Age"));
    }

    @Test
    void preflightToAProtectedRouteIsNotBlockedByTheTokenFilter() throws Exception {
        HttpResponse<String> response = preflight("/test/protected", ORIGIN, "GET");

        assertEquals(204, response.statusCode());
        assertEquals(Optional.of(ORIGIN), allowOrigin(response));
    }

    @Test
    void listsAreFixedWhateverTheBrowserAsks() throws Exception {
        HttpResponse<String> response = app.send(app.request("/auth/login")
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "x-custom")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build());

        assertEquals(Optional.of("GET, POST, OPTIONS"),
                response.headers().firstValue("Access-Control-Allow-Methods"));
        assertEquals(Optional.of("Authorization, Content-Type"),
                response.headers().firstValue("Access-Control-Allow-Headers"));
    }

    @Test
    void preflightFromAnotherOriginGetsNoPermission() throws Exception {
        HttpResponse<String> response = preflight("/auth/login", "http://evil.example", "POST");

        assertNoCorsPermission(response, "http://evil.example");
        assertEquals(405, response.statusCode(), "segue como sem CORS: OPTIONS numa rota só POST");
    }

    @Test
    void optionsWithoutRequestMethodIsNotAPreflight() throws Exception {
        HttpResponse<String> response = app.send(app.request("/auth/login")
                .header("Origin", ORIGIN)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build());

        assertEquals(405, response.statusCode());
        assertEquals(Optional.of(ORIGIN), allowOrigin(response));
        assertEquals(Optional.empty(), response.headers().firstValue("Access-Control-Allow-Methods"));
    }

    // ---------- comparação exata da origem ----------

    @Test
    void originComparisonIsExactInThePreflight() throws Exception {
        for (String origin : NOT_ALLOWED) {
            assertNoCorsPermission(preflight("/auth/login", origin, "POST"), origin);
            assertNoCorsPermission(preflight("/test/protected", origin, "GET"), origin);
        }
    }

    @Test
    void originComparisonIsExactInTheActualRequest() throws Exception {
        for (String origin : NOT_ALLOWED) {
            HttpResponse<String> response = loginFrom(origin);

            assertEquals(200, response.statusCode(), origin);
            assertNoCorsPermission(response, origin);
        }
    }

    // ---------- requisição comum ----------

    @Test
    void actualRequestFromTheAllowedOriginCarriesAllowOrigin() throws Exception {
        HttpResponse<String> response = loginFrom(ORIGIN);

        assertEquals(200, response.statusCode());
        assertEquals(Optional.of(ORIGIN), allowOrigin(response));
        assertEquals(Optional.empty(), response.headers().firstValue("Access-Control-Allow-Methods"));
        assertEquals(Optional.empty(), response.headers().firstValue("Access-Control-Allow-Credentials"));
    }

    @Test
    void tokenInvalid401CarriesAllowOrigin() throws Exception {
        HttpResponse<String> response = app.send(app.request("/test/protected")
                .header("Origin", ORIGIN).GET().build());

        assertEquals(401, response.statusCode());
        assertEquals("{\"code\":\"TOKEN_INVALID\",\"message\":\"Token ausente, inválido ou expirado.\"}",
                response.body());
        assertEquals(Optional.of(ORIGIN), allowOrigin(response));
    }

    @Test
    void otherErrorsCarryAllowOriginToo() throws Exception {
        HttpResponse<String> notFound = app.send(app.request("/nao-existe").header("Origin", ORIGIN).GET().build());
        HttpResponse<String> badRequest = app.send(app.request("/auth/login")
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{"))
                .build());

        assertEquals(404, notFound.statusCode());
        assertEquals(Optional.of(ORIGIN), allowOrigin(notFound));
        assertEquals(400, badRequest.statusCode());
        assertEquals(Optional.of(ORIGIN), allowOrigin(badRequest));
    }

    @Test
    void requestWithoutOriginGetsNoAllowOrigin() throws Exception {
        HttpResponse<String> response = app.postJson("/auth/login", LOGIN_BODY);

        assertEquals(200, response.statusCode());
        assertNoCorsPermission(response, "sem Origin");
    }

    // ---------- Expose-Headers (Retry-After do 429 do CT17) ----------

    @Test
    void allowedOriginGetsRetryAfterExposedOnSuccessAndOnErrors() throws Exception {
        HttpResponse<String> ok = loginFrom(ORIGIN);
        HttpResponse<String> unauthorized = app.send(app.request("/test/protected")
                .header("Origin", ORIGIN).GET().build());
        HttpResponse<String> notFound = app.send(app.request("/nao-existe").header("Origin", ORIGIN).GET().build());

        assertEquals(List.of(200, 401, 404),
                List.of(ok.statusCode(), unauthorized.statusCode(), notFound.statusCode()));
        for (HttpResponse<String> response : List.of(ok, unauthorized, notFound)) {
            assertEquals(List.of("Retry-After"), exposeHeaders(response), response.uri().toString());
        }
    }

    @Test
    void notAllowedOriginAndNoOriginGetNoExposeHeaders() throws Exception {
        for (String origin : NOT_ALLOWED) {
            HttpResponse<String> unauthorized = app.send(app.request("/test/protected")
                    .header("Origin", origin).GET().build());

            assertEquals(401, unauthorized.statusCode(), origin);
            assertEquals(List.of(), exposeHeaders(unauthorized), origin);
            assertNoCorsPermission(unauthorized, origin);
        }

        HttpResponse<String> withoutOrigin = app.send(app.request("/test/protected").GET().build());

        assertEquals(401, withoutOrigin.statusCode());
        assertEquals(List.of(), exposeHeaders(withoutOrigin));
        assertNoCorsPermission(withoutOrigin, "sem Origin");
    }

    // ---------- Vary ----------

    @Test
    void varyOriginIsSentOnceOnEveryResponse() throws Exception {
        List<HttpResponse<String>> responses = List.of(
                loginFrom(ORIGIN),
                loginFrom("http://evil.example"),
                app.postJson("/auth/login", LOGIN_BODY),
                preflight("/auth/login", ORIGIN, "POST"),
                app.send(app.request("/test/protected").header("Origin", ORIGIN).GET().build()));

        for (HttpResponse<String> response : responses) {
            assertEquals(List.of("Origin"), varyValues(response.headers()), response.uri().toString());
        }
    }

    @Test
    void varyOriginDoesNotOverwriteAnExistingVary() throws Exception {
        // Um filtro anterior já gravou um Vary; o CorsFilter acrescenta, não substitui.
        Javalin javalin = Javalin.create(config -> {
            config.routes.before(ctx -> ctx.header("Vary", "Accept-Encoding"));
            config.routes.before(new CorsFilter(ORIGIN)::handle);
            config.routes.get("/x", ctx -> ctx.result("ok"));
        }).start(0);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + javalin.port() + "/x"))
                            .header("Origin", ORIGIN).build(),
                    HttpResponse.BodyHandlers.ofString());

            List<String> vary = varyValues(response.headers());
            assertTrue(vary.contains("Accept-Encoding"), vary.toString());
            assertTrue(vary.contains("Origin"), vary.toString());
            assertEquals(Optional.of(ORIGIN), allowOrigin(response));
        } finally {
            javalin.stop();
        }
    }
}
