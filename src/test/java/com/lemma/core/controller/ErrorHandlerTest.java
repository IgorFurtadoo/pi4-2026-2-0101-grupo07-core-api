package com.lemma.core.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lemma.core.config.JsonConfig;
import com.lemma.core.LogCapture;
import com.lemma.core.exception.ApiException;
import com.lemma.core.exception.RateLimitExceededException;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sobe um Javalin real numa porta livre com o handler central e rotas que existem
 * só neste teste: uma lança ApiException, outra uma exceção inesperada e a última
 * lê um corpo JSON.
 */
class ErrorHandlerTest {

    /** Corpo de teste com dois campos de texto, como o do CT1. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TextBody(String email, String password) {
    }

    private static Javalin app;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startServer() {
        app = Javalin.create(config -> {
            config.jsonMapper(JsonConfig.jsonMapper());
            ErrorHandler.register(config);
            config.routes.get("/test/api-error", ctx -> {
                throw new ApiException(409, "TEST_ERROR", "Mensagem de teste.");
            });
            config.routes.get("/test/rate-limit", ctx -> {
                throw new RateLimitExceededException(7);
            });
            config.routes.get("/test/unexpected", ctx -> {
                throw new IllegalStateException("detalhe-interno");
            });
            config.routes.post("/test/body", ctx -> {
                TextBody body = ctx.bodyAsClass(TextBody.class);
                ctx.result("ok " + (body.email() == null ? "sem-email" : "com-email"));
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

    private static HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertRequestInvalid(HttpResponse<String> response) {
        assertEquals(400, response.statusCode());
        assertEquals("{\"code\":\"REQUEST_INVALID\",\"message\":\"Corpo da requisição inválido.\"}",
                response.body());
    }

    @Test
    void apiExceptionBecomesStatusAndCode() throws Exception {
        HttpResponse<String> response = get("/test/api-error");

        assertEquals(409, response.statusCode());
        assertEquals("{\"code\":\"TEST_ERROR\",\"message\":\"Mensagem de teste.\"}", response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        assertTrue(response.headers().firstValue("Retry-After").isEmpty(), "só o 429 do limite leva Retry-After");
    }

    @Test
    void rateLimitExceededBecomes429WithRetryAfterAndWithoutLog() throws Exception {
        try (LogCapture logs = new LogCapture(ErrorHandler.class)) {
            HttpResponse<String> response = get("/test/rate-limit");

            assertEquals(429, response.statusCode());
            assertEquals("{\"code\":\"RATE_LIMIT_EXCEEDED\","
                    + "\"message\":\"Muitas requisições. Tente novamente em instantes.\"}", response.body());
            assertEquals(List.of("7"), response.headers().allValues("Retry-After"));
            assertEquals("", logs.all());
        }
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

    // ---------- corpo inválido ----------

    @Test
    void validBodyIsRead() throws Exception {
        HttpResponse<String> response = post("/test/body", "{\"email\":\"ana@example.com\"}");

        assertEquals(200, response.statusCode());
        assertEquals("ok com-email", response.body());
    }

    @Test
    void emptyBodyIsRequestInvalid() throws Exception {
        assertRequestInvalid(post("/test/body", ""));
    }

    @Test
    void malformedJsonIsRequestInvalidWithoutEchoingTheBody() throws Exception {
        HttpResponse<String> response = post("/test/body", "{\"email\":\"segredo-do-corpo");

        assertRequestInvalid(response);
        assertFalse(response.body().contains("segredo-do-corpo"));
    }

    @Test
    void wrongTypeIsRequestInvalid() throws Exception {
        assertRequestInvalid(post("/test/body", "{\"email\":123}"));
        assertRequestInvalid(post("/test/body", "{\"password\":true}"));
    }
}
