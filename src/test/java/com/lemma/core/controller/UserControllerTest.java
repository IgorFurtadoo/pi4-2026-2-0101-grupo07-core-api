package com.lemma.core.controller;

import com.lemma.core.Main;
import com.lemma.core.security.PasswordHasher;
import com.lemma.core.service.FakeBlockchainClient;
import com.lemma.core.service.InMemoryUserRepository;
import com.lemma.core.service.UserService;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * POST /users por HTTP real, com a mesma configuração do Main (Main.createApp),
 * UserService real, Blockchain Service falso e repository em memória.
 */
class UserControllerTest {

    private static final HttpClient http = HttpClient.newHttpClient();

    private static final String REQUEST_INVALID =
            "{\"code\":\"REQUEST_INVALID\",\"message\":\"Corpo da requisição inválido.\"}";

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final FakeBlockchainClient blockchain = new FakeBlockchainClient();
    private Javalin app;

    @BeforeEach
    void startServer() {
        UserService service = new UserService(repository, new PasswordHasher(4), blockchain);
        app = Main.createApp(new UserController(service)).start(0);
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private HttpResponse<String> postUsers(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/users"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
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
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/users")).build();

        assertEquals(405, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private static String code(HttpResponse<String> response) {
        String body = response.body();
        int start = body.indexOf("\"code\":\"") + "\"code\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }
}
