package com.lemma.core.security;

import com.lemma.core.LogCapture;
import com.lemma.core.TestApp;
import com.lemma.core.controller.ErrorHandler;
import com.lemma.core.model.User;
import com.lemma.core.service.FakeBlockchainClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TokenFilter por HTTP real, com a configuração do Main (TestApp) e uma rota
 * protegida que só existe neste teste (sem depender de banco nem do Blockchain
 * Service). A rota real de saldo é testada em WalletControllerTest.
 */
class TokenFilterTest {

    private static final String TOKEN_INVALID =
            "{\"code\":\"TOKEN_INVALID\",\"message\":\"Token ausente, inválido ou expirado.\"}";

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start(javalin -> javalin.routes.get("/test/protected",
                ctx -> ctx.result("userId=" + TokenFilter.userId(ctx))));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private HttpResponse<String> getProtected(String authorization) throws Exception {
        HttpRequest.Builder request = app.request("/test/protected").GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return app.send(request.build());
    }

    private void assertTokenInvalid(String authorization) throws Exception {
        HttpResponse<String> response = getProtected(authorization);

        assertEquals(401, response.statusCode(), authorization);
        assertEquals(TOKEN_INVALID, response.body(), authorization);
    }

    private String validToken(long userId) {
        return app.jwtService.issue(userId).token();
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 401 TOKEN_INVALID ----------

    @Test
    void withoutAuthorizationIs401() throws Exception {
        assertTokenInvalid(null);
    }

    @Test
    void otherSchemeOrNoTokenIs401() throws Exception {
        String token = validToken(1);

        assertTokenInvalid("Basic " + token);
        assertTokenInvalid("Token " + token);
        assertTokenInvalid(token);
        assertTokenInvalid("Bearer");
        assertTokenInvalid("Bearer ");
        assertTokenInvalid("Bearer    ");
        assertTokenInvalid("Bearer" + token);
    }

    @Test
    void expiredTokenIs401() throws Exception {
        // Emitido 5 h antes do relógio do app, com validade de 4 h.
        JwtService earlier = new JwtService(TestApp.JWT_SECRET, TestApp.JWT_TTL_MINUTES,
                Clock.fixed(TestApp.NOW.minus(Duration.ofHours(5)), ZoneOffset.UTC));

        assertTokenInvalid("Bearer " + earlier.issue(1).token());
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() throws Exception {
        JwtService other = new JwtService("outro-segredo-de-teste-falso-com-mais-de-32-bytes",
                TestApp.JWT_TTL_MINUTES, Clock.fixed(TestApp.NOW, ZoneOffset.UTC));

        assertTokenInvalid("Bearer " + other.issue(1).token());
    }

    @Test
    void algNoneIs401() throws Exception {
        long now = TestApp.NOW.getEpochSecond();
        String forged = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                + base64Url("{\"sub\":\"1\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}") + ".";

        assertTokenInvalid("Bearer " + forged);
    }

    @Test
    void garbageTokenIs401() throws Exception {
        assertTokenInvalid("Bearer lixo");
        assertTokenInvalid("Bearer a.b.c");
    }

    @Test
    void the401IsNotLogged() throws Exception {
        try (LogCapture logs = new LogCapture(ErrorHandler.class)) {
            assertTokenInvalid("Bearer lixo-que-nao-pode-ir-ao-log");

            assertEquals(0, logs.lines().size());
        }
    }

    // ---------- token válido ----------

    @Test
    void validTokenGives200AndTheUserIdReachesTheController() throws Exception {
        HttpResponse<String> response = getProtected("Bearer " + validToken(42));

        assertEquals(200, response.statusCode());
        assertEquals("userId=42", response.body());
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        assertEquals("userId=7", getProtected("bearer " + validToken(7)).body());
        assertEquals("userId=7", getProtected("BEARER " + validToken(7)).body());
    }

    // ---------- rotas públicas ----------

    @Test
    void publicRoutesWorkWithoutToken() throws Exception {
        HttpResponse<String> register = app.postJson("/users",
                "{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}");
        HttpResponse<String> login = app.postJson("/auth/login",
                "{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}");

        assertEquals(201, register.statusCode());
        assertEquals(200, login.statusCode());
    }

    @Test
    void publicRoutesIgnoreAnInvalidToken() throws Exception {
        app.repository.save(new User("ana@example.com", app.hasher.hash("Senha@12345"),
                FakeBlockchainClient.address(1), TestApp.NOW));

        HttpResponse<String> login = app.send(app.request("/auth/login")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer lixo")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}"))
                .build());

        assertEquals(200, login.statusCode());
    }

    @Test
    void publicRouteWithTrailingSlashIsStillPublic() throws Exception {
        // A chave é o padrão registrado ("/users"), não o caminho pedido.
        HttpResponse<String> response = app.postJson("/users/",
                "{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}");

        assertEquals(201, response.statusCode());
    }

    @Test
    void protectedRouteWithTrailingSlashIsStillProtected() throws Exception {
        HttpResponse<String> response = app.send(app.request("/test/protected/").GET().build());

        assertEquals(401, response.statusCode());
        assertEquals(TOKEN_INVALID, response.body());
    }

    // ---------- 404 e 405 continuam como estavam ----------

    @Test
    void unknownRouteIs404WithAndWithoutToken() throws Exception {
        HttpResponse<String> withoutToken = app.send(app.request("/rota-inexistente").GET().build());
        HttpResponse<String> withToken = app.send(app.request("/rota-inexistente")
                .header("Authorization", "Bearer " + validToken(1)).GET().build());
        HttpResponse<String> otherMethod = app.send(app.request("/rota-inexistente")
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        // Rota sem handler: 404, não 401 (o filtro só roda em rota casada).
        assertEquals(404, withoutToken.statusCode());
        assertEquals(404, withToken.statusCode());
        assertEquals(404, otherMethod.statusCode());
    }

    @Test
    void wrongMethodIs405NotTokenInvalid() throws Exception {
        HttpResponse<String> postOnProtected = app.send(app.request("/test/protected")
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        HttpResponse<String> getOnUsers = app.send(app.request("/users").GET().build());
        HttpResponse<String> getOnLogin = app.send(app.request("/auth/login").GET().build());

        assertEquals(405, postOnProtected.statusCode());
        assertEquals(405, getOnUsers.statusCode());
        assertEquals(405, getOnLogin.statusCode());
    }

    @Test
    void headOnAProtectedGetRouteRequiresToken() throws Exception {
        // O Javalin responde HEAD pela rota GET; sem rota casada própria, o filtro exige token.
        HttpResponse<String> withoutToken = app.send(app.request("/test/protected")
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        HttpResponse<String> withToken = app.send(app.request("/test/protected")
                .header("Authorization", "Bearer " + validToken(1))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build());

        assertEquals(401, withoutToken.statusCode());
        assertEquals(200, withToken.statusCode());
    }
}
