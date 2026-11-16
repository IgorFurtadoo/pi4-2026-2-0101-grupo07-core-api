package com.lemma.core.controller;

import com.lemma.core.TestApp;
import com.lemma.core.model.User;
import com.lemma.core.service.FakeBlockchainClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * POST /auth/login por HTTP real, com a mesma configuração do Main (TestApp).
 * O relógio é fixo e o HS256 é determinístico: o token esperado é o que o próprio
 * JwtService emite para o usuário 1 no mesmo instante.
 */
class AuthControllerTest {

    private static final String PASSWORD = "Senha@12345";

    private static final String REQUEST_INVALID =
            "{\"code\":\"REQUEST_INVALID\",\"message\":\"Corpo da requisição inválido.\"}";
    private static final String INVALID_CREDENTIALS =
            "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"E-mail ou senha incorretos\"}";

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start();
        app.repository.save(new User("ana@example.com", app.hasher.hash(PASSWORD),
                FakeBlockchainClient.address(1), TestApp.NOW));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private HttpResponse<String> login(String body) throws Exception {
        return app.postJson("/auth/login", body);
    }

    private void assertRequestInvalid(String body) throws Exception {
        HttpResponse<String> response = login(body);

        assertEquals(400, response.statusCode(), body);
        assertEquals(REQUEST_INVALID, response.body(), body);
    }

    @Test
    void loginReturns200WithExactlyTokenAndExpiresAt() throws Exception {
        HttpResponse<String> response = login("{\"email\":\"ana@example.com\",\"password\":\"" + PASSWORD + "\"}");

        assertEquals(200, response.statusCode());
        assertEquals("{\"token\":\"" + app.jwtService.issue(1).token()
                + "\",\"expiresAt\":\"2026-10-20T18:30:00Z\"}", response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void emailWithUppercaseAndSpacesLogsIn() throws Exception {
        HttpResponse<String> response = login("{\"email\":\" Ana@Example.COM \",\"password\":\"" + PASSWORD + "\"}");

        assertEquals(200, response.statusCode());
    }

    @Test
    void extraFieldIsIgnored() throws Exception {
        HttpResponse<String> response = login(
                "{\"email\":\"ana@example.com\",\"password\":\"" + PASSWORD + "\",\"admin\":true}");

        assertEquals(200, response.statusCode());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetIdentical401() throws Exception {
        HttpResponse<String> wrongPassword = login("{\"email\":\"ana@example.com\",\"password\":\"Senha@99999\"}");
        HttpResponse<String> unknownEmail = login("{\"email\":\"bia@example.com\",\"password\":\"" + PASSWORD + "\"}");

        assertEquals(401, wrongPassword.statusCode());
        assertEquals(401, unknownEmail.statusCode());
        assertEquals(INVALID_CREDENTIALS, wrongPassword.body());
        assertEquals(INVALID_CREDENTIALS, unknownEmail.body());
    }

    @Test
    void passwordOf73BytesIs401() throws Exception {
        HttpResponse<String> response = login("{\"email\":\"ana@example.com\",\"password\":\"" + "a".repeat(73) + "\"}");

        assertEquals(401, response.statusCode());
        assertEquals(INVALID_CREDENTIALS, response.body());
    }

    @Test
    void emptyBodyIsRequestInvalid() throws Exception {
        assertRequestInvalid("");
    }

    @Test
    void malformedJsonIsRequestInvalidWithoutEchoingTheBody() throws Exception {
        HttpResponse<String> response = login("{\"email\":\"ana@example.com\",\"password\":\"SenhaSecreta");

        assertEquals(400, response.statusCode());
        assertEquals(REQUEST_INVALID, response.body());
        assertFalse(response.body().contains("SenhaSecreta"));
        assertFalse(response.body().contains("ana@example.com"));
    }

    @Test
    void literalNullAndNonObjectBodiesAreRequestInvalid() throws Exception {
        assertRequestInvalid("null");
        assertRequestInvalid(" null ");
        assertRequestInvalid("[]");
        assertRequestInvalid("\"ana@example.com\"");
        assertRequestInvalid("123");
    }

    @Test
    void wrongFieldTypesAreRequestInvalid() throws Exception {
        assertRequestInvalid("{\"email\":123,\"password\":\"" + PASSWORD + "\"}");
        assertRequestInvalid("{\"email\":\"ana@example.com\",\"password\":true}");
        assertRequestInvalid("{\"email\":\"ana@example.com\",\"password\":12345678}");
        assertRequestInvalid("{\"email\":[\"ana@example.com\"],\"password\":\"" + PASSWORD + "\"}");
        assertRequestInvalid("{\"email\":\"ana@example.com\",\"password\":{}}");
    }

    @Test
    void missingOrEmptyFieldsAreRequestInvalid() throws Exception {
        assertRequestInvalid("{}");
        assertRequestInvalid("{\"password\":\"" + PASSWORD + "\"}");
        assertRequestInvalid("{\"email\":\"ana@example.com\"}");
        assertRequestInvalid("{\"email\":null,\"password\":\"" + PASSWORD + "\"}");
        assertRequestInvalid("{\"email\":\"   \",\"password\":\"" + PASSWORD + "\"}");
        assertRequestInvalid("{\"email\":\"ana@example.com\",\"password\":\"\"}");
    }

    @Test
    void getOnAuthLoginIs405() throws Exception {
        assertEquals(405, app.send(app.request("/auth/login").GET().build()).statusCode());
    }
}
