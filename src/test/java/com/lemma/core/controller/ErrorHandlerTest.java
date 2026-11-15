package com.lemma.core.controller;

import com.lemma.core.exception.ApiException;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Sobe um Javalin real numa porta livre com o handler central e duas rotas que
 * existem só neste teste: uma lança ApiException e a outra uma exceção inesperada.
 */
class ErrorHandlerTest {

    private static Javalin app;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startServer() {
        app = Javalin.create(config -> {
            ErrorHandler.register(config);
            config.routes.get("/test/api-error", ctx -> {
                throw new ApiException(409, "TEST_ERROR", "Mensagem de teste.");
            });
            config.routes.get("/test/unexpected", ctx -> {
                throw new IllegalStateException("detalhe-interno");
            });
        }).start(0);
    }

    @AfterAll
    static void stopServer() {
        app.stop();
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void apiExceptionBecomesStatusAndCode() throws Exception {
        HttpResponse<String> response = get("/test/api-error");

        assertEquals(409, response.statusCode());
        assertEquals("{\"code\":\"TEST_ERROR\",\"message\":\"Mensagem de teste.\"}", response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void unexpectedExceptionBecomesInternalErrorWithoutDetails() throws Exception {
        HttpResponse<String> response = get("/test/unexpected");

        assertEquals(500, response.statusCode());
        assertEquals("{\"code\":\"INTERNAL_ERROR\",\"message\":\"Erro interno.\"}", response.body());
        assertFalse(response.body().contains("detalhe-interno"));
        assertFalse(response.body().contains("IllegalStateException"));
    }

    @Test
    void unknownRouteKeepsJavalinDefault404() throws Exception {
        HttpResponse<String> response = get("/nao-existe");

        assertEquals(404, response.statusCode());
        assertFalse(response.body().contains("\"code\""));
        assertFalse(response.body().contains("INTERNAL_ERROR"));
    }
}
