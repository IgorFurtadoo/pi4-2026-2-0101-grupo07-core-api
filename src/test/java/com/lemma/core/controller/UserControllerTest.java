package com.lemma.core.controller;

import com.lemma.core.TestApp;
import com.lemma.core.service.FakeBlockchainClient;
import com.lemma.core.service.InMemoryUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * POST /users por HTTP real, com a mesma configuração do Main (TestApp),
 * UserService real, Blockchain Service falso e repository em memória.
 */
class UserControllerTest {

    private static final String REQUEST_INVALID =
            "{\"code\":\"REQUEST_INVALID\",\"message\":\"Corpo da requisição inválido.\"}";

    private final TestApp app = new TestApp();
    private final InMemoryUserRepository repository = app.repository;
    private final FakeBlockchainClient blockchain = app.blockchain;

    @BeforeEach
    void startServer() {
        app.start();
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private HttpResponse<String> postUsers(String body) throws IOException, InterruptedException {
        return app.postJson("/users", body);
    }

    private void assertRequestInvalid(String body) throws Exception {
        HttpResponse<String> response = postUsers(body);

        assertEquals(400, response.statusCode(), body);
        assertEquals(REQUEST_INVALID, response.body(), body);
        assertEquals(0, blockchain.createWalletCalls);
        assertEquals(0, repository.saveCalls);
    }

    @Test
    void registersAndReturns201WithExactlyTheThreeFields() throws Exception {
        HttpResponse<String> response = postUsers("{\"email\":\" Ana@Example.com \",\"password\":\"Senha@12345\"}");

        assertEquals(201, response.statusCode());
        assertEquals("{\"userId\":1,\"email\":\"ana@example.com\",\"ethereumAddress\":\""
                + FakeBlockchainClient.address(1) + "\"}", response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void extraFieldIsIgnored() throws Exception {
        HttpResponse<String> response = postUsers(
                "{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\",\"admin\":true}");

        assertEquals(201, response.statusCode());
    }

    @Test
    void emptyBodyIsRequestInvalid() throws Exception {
        assertRequestInvalid("");
    }

    @Test
    void malformedJsonIsRequestInvalidWithoutEchoingTheBody() throws Exception {
        HttpResponse<String> response = postUsers("{\"email\":\"ana@example.com\",\"password\":\"SenhaSecreta");

        assertEquals(400, response.statusCode());
        assertEquals(REQUEST_INVALID, response.body());
        assertFalse(response.body().contains("SenhaSecreta"));
        assertFalse(response.body().contains("ana@example.com"));
    }

    @Test
    void literalNullAndNonObjectBodiesAreRequestInvalid() throws Exception {
        assertRequestInvalid("null");
        assertRequestInvalid("[]");
        assertRequestInvalid("\"ana@example.com\"");
        assertRequestInvalid("123");
    }

    @Test
    void wrongFieldTypesAreRequestInvalid() throws Exception {
        assertRequestInvalid("{\"email\":123,\"password\":\"Senha@12345\"}");
        assertRequestInvalid("{\"email\":\"ana@example.com\",\"password\":true}");
        assertRequestInvalid("{\"email\":\"ana@example.com\",\"password\":12345678}");
        assertRequestInvalid("{\"email\":[\"ana@example.com\"],\"password\":\"Senha@12345\"}");
    }

    @Test
    void missingFieldsUseTheFieldCode() throws Exception {
        HttpResponse<String> noEmail = postUsers("{\"password\":\"Senha@12345\"}");
        HttpResponse<String> noPassword = postUsers("{\"email\":\"ana@example.com\"}");

        assertEquals(400, noEmail.statusCode());
        assertEquals("EMAIL_INVALID", code(noEmail));
        assertEquals(400, noPassword.statusCode());
        assertEquals("PASSWORD_INVALID", code(noPassword));
    }

    @Test
    void repeatedEmailIs409() throws Exception {
        postUsers("{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}");

        HttpResponse<String> response = postUsers("{\"email\":\"ANA@example.com\",\"password\":\"Senha@12345\"}");

        assertEquals(409, response.statusCode());
        assertEquals("EMAIL_ALREADY_REGISTERED", code(response));
    }

    @Test
    void walletFailureIs502() throws Exception {
        blockchain.fail = true;

        HttpResponse<String> response = postUsers("{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}");

        assertEquals(502, response.statusCode());
        assertEquals("WALLET_UNAVAILABLE", code(response));
    }

    @Test
    void getOnUsersIs405() throws Exception {
        assertEquals(405, app.send(app.request("/users").GET().build()).statusCode());
    }

    private static String code(HttpResponse<String> response) {
        return TestApp.code(response);
    }
}
