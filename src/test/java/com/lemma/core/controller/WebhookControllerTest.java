package com.lemma.core.controller;

import com.lemma.core.LogCapture;
import com.lemma.core.TestApp;
import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;
import com.lemma.core.model.User;
import com.lemma.core.service.FakeBlockchainClient;
import com.lemma.core.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * POST /webhooks/tasks (CT15) por HTTP real, com a mesma configuração do Main
 * (TestApp): sem token, protegido pelo ServiceKeyFilter. A transação de ana
 * (usuário 1) está gravada como PENDING.
 */
class WebhookControllerTest {

    private static final String TASK = "a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b";
    private static final String DESTINATION = "0x5B38Da6a701c568545dCfcB03FcB875f56beddC4";
    private static final String HASH = "0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060";
    private static final Instant CREATED = TestApp.NOW.minusSeconds(60);

    private static final String SERVICE_KEY_INVALID =
            "{\"code\":\"SERVICE_KEY_INVALID\",\"message\":\"Chave de serviço ausente ou incorreta.\"}";
    private static final String REQUEST_INVALID =
            "{\"code\":\"REQUEST_INVALID\",\"message\":\"Corpo da requisição inválido.\"}";
    private static final String NOT_FOUND =
            "{\"code\":\"TRANSACTION_NOT_FOUND\",\"message\":\"Transação não encontrada.\"}";

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start();
        app.repository.save(new User("ana@example.com", "hash-falso", FakeBlockchainClient.address(1), TestApp.NOW));
        app.transactions.save(new EthTransaction(TASK, 1, DESTINATION, new BigDecimal("0.01").setScale(18), CREATED));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private static String body(String taskId, String status, String hash, String reason) {
        StringBuilder json = new StringBuilder("{\"taskId\":\"" + taskId + "\",\"status\":\"" + status + "\"");
        if (hash != null) {
            json.append(",\"transactionHash\":\"").append(hash).append('"');
        }
        if (reason != null) {
            json.append(",\"failureReason\":\"").append(reason).append('"');
        }
        return json.append(",\"updatedAt\":\"2026-10-20T14:36:10Z\"}").toString();
    }

    private static final String CONFIRMED = body(TASK, "CONFIRMED", HASH, null);

    private HttpResponse<String> post(String serviceKey, String authorization, String body) throws Exception {
        HttpRequest.Builder request = app.request("/webhooks/tasks")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (serviceKey != null) {
            request.header("X-Service-Key", serviceKey);
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return app.send(request.build());
    }

    private HttpResponse<String> post(String body) throws Exception {
        return post(TestApp.SERVICE_KEY, null, body);
    }

    private void assertRowUntouched() {
        EthTransaction row = app.transactions.row(TASK);
        assertEquals(TransactionStatus.PENDING, row.getStatus());
        assertEquals(CREATED, row.getUpdatedAt());
    }

    // ---------- chave de serviço ----------

    @Test
    void missingWrongOrEmptyKeyIs401() throws Exception {
        for (String key : new String[] {null, "outra-chave", "", TestApp.SERVICE_KEY + "x"}) {
            HttpResponse<String> response = post(key, null, CONFIRMED);

            assertEquals(401, response.statusCode(), key);
            assertEquals(SERVICE_KEY_INVALID, response.body(), key);
        }
        assertRowUntouched();
    }

    @Test
    void userTokenDoesNotOpenTheWebhook() throws Exception {
        String token = app.jwtService.issue(1).token();

        HttpResponse<String> asAuthorization = post(null, "Bearer " + token, CONFIRMED);
        HttpResponse<String> asServiceKey = post(token, null, CONFIRMED);

        for (HttpResponse<String> response : List.of(asAuthorization, asServiceKey)) {
            assertEquals(401, response.statusCode());
            assertEquals(SERVICE_KEY_INVALID, response.body());
        }
        assertRowUntouched();
    }

    @Test
    void keyIsCheckedBeforeTheBody() throws Exception {
        HttpResponse<String> response = post(null, null, "{");

        assertEquals(401, response.statusCode());
        assertEquals(SERVICE_KEY_INVALID, response.body());
    }

    // ---------- 204 ----------

    @Test
    void rightKeyAppliesTheStateAndReturns204WithoutBody() throws Exception {
        HttpResponse<String> response = post(CONFIRMED);

        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
        EthTransaction row = app.transactions.row(TASK);
        assertEquals(TransactionStatus.CONFIRMED, row.getStatus());
        assertEquals(HASH, row.getTransactionHash());
        assertEquals(TestApp.NOW, row.getUpdatedAt());
    }

    @Test
    void failedWithoutHashIsAccepted() throws Exception {
        HttpResponse<String> response = post(body(TASK, "FAILED", null, "REJECTED_BY_NETWORK"));

        assertEquals(204, response.statusCode());
        EthTransaction row = app.transactions.row(TASK);
        assertEquals(TransactionStatus.FAILED, row.getStatus());
        assertNull(row.getTransactionHash());
        assertEquals(FailureReason.REJECTED_BY_NETWORK, row.getFailureReason());
    }

    @Test
    void extraFieldsAreIgnored() throws Exception {
        HttpResponse<String> response = post("{\"outro\":1," + CONFIRMED.substring(1));

        assertEquals(204, response.statusCode());
    }

    @Test
    void resendIs204AndChangesNothingWithoutLog() throws Exception {
        post(CONFIRMED);
        app.transactions.setState(TASK, TransactionStatus.CONFIRMED, HASH, null, CREATED);

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            HttpResponse<String> response = post(CONFIRMED);

            assertEquals(204, response.statusCode());
            assertEquals(0, logs.lines().size());
        }
        assertEquals(CREATED, app.transactions.row(TASK).getUpdatedAt());
    }

    @Test
    void contradictionIs204ChangesNothingAndLogsAWarning() throws Exception {
        app.transactions.setState(TASK, TransactionStatus.FAILED, null, FailureReason.REJECTED_BY_NETWORK, CREATED);

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            HttpResponse<String> response = post(CONFIRMED);

            assertEquals(204, response.statusCode());
            assertEquals("WARN Task " + TASK + ": notificação contradiz o estado final"
                    + " (FAILED gravado, CONFIRMED notificado)", logs.all());
        }
        assertEquals(TransactionStatus.FAILED, app.transactions.row(TASK).getStatus());
        assertEquals(CREATED, app.transactions.row(TASK).getUpdatedAt());
    }

    // ---------- 404 ----------

    @Test
    void unknownTaskIs404() throws Exception {
        HttpResponse<String> response = post(body("b4e2d3c5-6c7f-4a81-9ba2-1d3e4f5a6b7c", "CONFIRMED", HASH, null));

        assertEquals(404, response.statusCode());
        assertEquals(NOT_FOUND, response.body());
    }

    // ---------- 400 REQUEST_INVALID ----------

    @Test
    void invalidBodiesAre400() throws Exception {
        List<String> bodies = List.of(
                "", "{", "null", "[]", "\"texto\"", "isto não é JSON",
                "{\"taskId\":123,\"status\":\"CONFIRMED\",\"transactionHash\":\"" + HASH
                        + "\",\"updatedAt\":\"2026-10-20T14:36:10Z\"}",
                "{\"taskId\":\"" + TASK + "\",\"status\":1,\"updatedAt\":\"2026-10-20T14:36:10Z\"}",
                "{\"taskId\":\"" + TASK + "\",\"status\":\"CONFIRMED\",\"transactionHash\":true,"
                        + "\"updatedAt\":\"2026-10-20T14:36:10Z\"}",
                "{\"taskId\":\"" + TASK + "\",\"status\":\"CONFIRMED\",\"transactionHash\":\"" + HASH
                        + "\",\"updatedAt\":1700000000}",
                body(TASK, "PENDING", null, null),
                body(TASK, "confirmed", HASH, null),
                body("abc", "CONFIRMED", HASH, null),
                body(TASK, "FAILED", null, null),
                body(TASK, "FAILED", null, "OUTRO"),
                body(TASK, "CONFIRMED", null, null),
                body(TASK, "CONFIRMED", "0x123", null),
                "{\"taskId\":\"" + TASK + "\",\"status\":\"CONFIRMED\",\"transactionHash\":\"" + HASH + "\"}",
                "{\"taskId\":\"" + TASK + "\",\"status\":\"CONFIRMED\",\"transactionHash\":\"" + HASH
                        + "\",\"updatedAt\":\"ontem\"}");

        for (String body : bodies) {
            HttpResponse<String> response = post(body);

            assertEquals(400, response.statusCode(), body);
            assertEquals(REQUEST_INVALID, response.body(), body);
        }
        assertRowUntouched();
        assertEquals(0, app.transactions.updateCalls);
    }

    // ---------- 405 ----------

    @Test
    void getIs405() throws Exception {
        HttpResponse<String> response = app.send(app.request("/webhooks/tasks")
                .header("X-Service-Key", TestApp.SERVICE_KEY).GET().build());

        assertEquals(405, response.statusCode());
    }
}
