package com.lemma.core.security;

import com.lemma.core.LogCapture;
import com.lemma.core.controller.ErrorHandler;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServiceKeyFilter por HTTP real, num Javalin próprio com o handler de erros e
 * duas rotas que só existem neste teste: uma que exige a chave e outra fora da
 * lista, que passa sem chave. A rota real do webhook é testada em WebhookControllerTest.
 */
class ServiceKeyFilterTest {

    /** Valor obviamente falso, só para os testes. */
    private static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";
    private static final String SERVICE_KEY_INVALID =
            "{\"code\":\"SERVICE_KEY_INVALID\",\"message\":\"Chave de serviço ausente ou incorreta.\"}";

    private static final HttpClient http = HttpClient.newHttpClient();

    private Javalin app;

    @AfterEach
    void stopServer() {
        if (app != null) {
            app.stop();
        }
    }

    private void startServer(String serviceKey) {
        ServiceKeyFilter filter = new ServiceKeyFilter(serviceKey, Set.of("POST /test/webhook"));
        app = Javalin.create(javalin -> {
            ErrorHandler.register(javalin);
            javalin.routes.beforeMatched(filter::handle);
            javalin.routes.post("/test/webhook", ctx -> ctx.status(204));
            javalin.routes.get("/test/other", ctx -> ctx.result("ok"));
        }).start(0);
    }

    private HttpResponse<String> post(String serviceKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port() + "/test/webhook"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (serviceKey != null) {
            request.header("X-Service-Key", serviceKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 401 exato, WARN sem a chave (nem a esperada nem a enviada). */
    private void assertRefused(String serviceKey) throws Exception {
        try (LogCapture logs = new LogCapture(ServiceKeyFilter.class)) {
            HttpResponse<String> response = post(serviceKey);

            assertEquals(401, response.statusCode(), serviceKey);
            assertEquals(SERVICE_KEY_INVALID, response.body(), serviceKey);
            assertEquals("WARN Chave de serviço ausente ou incorreta em POST /test/webhook", logs.all(), serviceKey);
            assertFalse(logs.all().contains(SERVICE_KEY), serviceKey);
            if (serviceKey != null && !serviceKey.isEmpty()) {
                assertFalse(logs.all().contains(serviceKey), serviceKey);
            }
        }
    }

    @Test
    void rightKeyPassesWithoutLog() throws Exception {
        startServer(SERVICE_KEY);

        try (LogCapture logs = new LogCapture(ServiceKeyFilter.class)) {
            assertEquals(204, post(SERVICE_KEY).statusCode());
            assertEquals(0, logs.lines().size());
        }
    }

    @Test
    void missingEmptyOrWrongKeyIs401() throws Exception {
        startServer(SERVICE_KEY);

        assertRefused(null);
        assertRefused("");
        assertRefused("outra-chave-qualquer");
        assertRefused(SERVICE_KEY + "x");                                   // um caractere a mais
        assertRefused(SERVICE_KEY.substring(0, SERVICE_KEY.length() - 1));  // um a menos
        assertRefused("x" + SERVICE_KEY.substring(1));                      // primeiro diferente
        assertRefused(SERVICE_KEY.toUpperCase());
        assertRefused("Bearer " + SERVICE_KEY);
    }

    @Test
    void emptyHeaderIsRefusedEvenIfTheConfiguredKeyWereEmpty() throws Exception {
        startServer("");

        assertRefused("");
        assertRefused(null);
    }

    @Test
    void routesOutsideTheListDoNotNeedTheKey() throws Exception {
        startServer(SERVICE_KEY);

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port() + "/test/other")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    @Test
    void unknownRouteKeepsTheJavalinDefault() throws Exception {
        startServer(SERVICE_KEY);

        HttpResponse<String> notFound = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port() + "/test/nada")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, notFound.statusCode());
        assertFalse(notFound.body().contains("SERVICE_KEY_INVALID"));
    }

    /**
     * O tempo da comparação não é medido (seria um teste instável); o código usa
     * MessageDigest.isEqual. Aqui ficam os casos em que uma comparação ingênua
     * (prefixo, tamanho) costuma errar.
     */
    @Test
    void isValidAcceptsOnlyTheExactKey() {
        ServiceKeyFilter filter = new ServiceKeyFilter(SERVICE_KEY, Set.of());

        assertTrue(filter.isValid(SERVICE_KEY));
        assertFalse(filter.isValid(null));
        assertFalse(filter.isValid(""));
        assertFalse(filter.isValid(SERVICE_KEY.substring(0, 5)));
        assertFalse(filter.isValid(SERVICE_KEY + SERVICE_KEY));
        assertFalse(filter.isValid(SERVICE_KEY + "\u0000"));
        assertFalse(new ServiceKeyFilter("", Set.of()).isValid(""));
    }
}
