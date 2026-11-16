package com.lemma.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Três grupos de rota (seção 6 do desenho), com falha fechada:
 * - abertas, sem credencial: só o cadastro e o login (premissa P1);
 * - entre serviços: sem token, mas com X-Service-Key (hoje, só o webhook CT15);
 * - todas as outras, como GET /wallet/balance (CT6), POST /transactions (CT9) e
 *   GET /transactions/{taskId} (CT13), exigem token sem entrar em lista nenhuma.
 *
 * Uma rota sem token só existe se estiver em OPEN_ROUTES (lista fixada aqui) ou em
 * SERVICE_KEY_ROUTES (que o teste HTTP percorre, exigindo a chave de cada uma).
 */
class MainRoutesTest {

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start();
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    @Test
    void onlyRegisterAndLoginAreOpen() {
        assertEquals(Set.of("POST /users", "POST /auth/login"), Main.OPEN_ROUTES);
    }

    @Test
    void onlyTheWebhookIsAServiceRoute() {
        assertEquals(Set.of("POST /webhooks/tasks"), Main.SERVICE_KEY_ROUTES);
    }

    @Test
    void everyRouteWithoutTokenIsOpenOrNeedsTheServiceKey() {
        for (String route : Main.PUBLIC_ROUTES) {
            assertTrue(Main.OPEN_ROUTES.contains(route) || Main.SERVICE_KEY_ROUTES.contains(route), route);
        }
        Set<String> union = new HashSet<>(Main.OPEN_ROUTES);
        union.addAll(Main.SERVICE_KEY_ROUTES);
        assertEquals(union, Main.PUBLIC_ROUTES);
    }

    /** Cada rota entre serviços, de verdade: sem chave, 401, mesmo com um token de usuário válido. */
    @Test
    void everyServiceRouteRefusesRequestsWithoutTheKey() throws Exception {
        String token = app.jwtService.issue(1).token();

        for (String route : Main.SERVICE_KEY_ROUTES) {
            String method = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1);
            for (String authorization : new String[] {null, "Bearer " + token}) {
                HttpRequest.Builder request = app.request(path)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString("{}"));
                if (authorization != null) {
                    request.header("Authorization", authorization);
                }
                HttpResponse<String> response = app.send(request.build());

                assertEquals(401, response.statusCode(), route);
                assertEquals("SERVICE_KEY_INVALID", TestApp.code(response), route);
            }
        }
    }
}
