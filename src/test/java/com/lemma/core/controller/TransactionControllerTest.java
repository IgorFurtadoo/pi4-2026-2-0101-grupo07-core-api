package com.lemma.core.controller;

import com.lemma.core.TestApp;
import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.User;
import com.lemma.core.security.JwtService;
import com.lemma.core.service.FakeBlockchainClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /transactions por HTTP real, com a mesma configuração do Main (TestApp):
 * a rota real, protegida pelo TokenFilter sem estar em PUBLIC_ROUTES. O usuário 1
 * (ana) está gravado; o Blockchain Service e os repositórios são os falsos.
 */
class TransactionControllerTest {

    private static final String ADDRESS = FakeBlockchainClient.address(1);
    private static final String DESTINATION = "0x5B38Da6a701c568545dCfcB03FcB875f56beddC4";
    private static final String TASK_ID = FakeBlockchainClient.taskId(1);

    private static final String ACCEPTED = "{\"taskId\":\"" + TASK_ID + "\",\"status\":\"PENDING\"}";
    private static final String TOKEN_INVALID =
            "{\"code\":\"TOKEN_INVALID\",\"message\":\"Token ausente, inválido ou expirado.\"}";
    private static final String REQUEST_INVALID =
            "{\"code\":\"REQUEST_INVALID\",\"message\":\"Corpo da requisição inválido.\"}";
    private static final String ADDRESS_INVALID =
            "{\"code\":\"ADDRESS_INVALID\",\"message\":\"Informe um endereço Ethereum válido.\"}";
    private static final String AMOUNT_INVALID = "{\"code\":\"AMOUNT_INVALID\",\"message\":\"Informe um valor"
            + " maior que zero, com no máximo 18 casas decimais e 20 dígitos inteiros.\"}";
    private static final String BLOCKCHAIN_UNAVAILABLE = "{\"code\":\"BLOCKCHAIN_UNAVAILABLE\","
            + "\"message\":\"Não foi possível enviar a transação agora. Tente novamente.\"}";

    private final TestApp app = new TestApp();

    @BeforeEach
    void startServer() {
        app.start();
        app.repository.save(new User("ana@example.com", "hash-falso", ADDRESS, TestApp.NOW));
    }

    @AfterEach
    void stopServer() {
        app.stop();
    }

    private String bearer(long userId) {
        return "Bearer " + app.jwtService.issue(userId).token();
    }

    private HttpResponse<String> post(String authorization, String body) throws Exception {
        return post(authorization, body, null);
    }

    private HttpResponse<String> post(String authorization, String body, String origin) throws Exception {
        HttpRequest.Builder request = app.request("/transactions")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return app.send(request.build());
    }

    private HttpResponse<String> send(String amountJson) throws Exception {
        return post(bearer(1), "{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":" + amountJson + "}");
    }

    /** Resposta 400 exata, sem CT10 e sem gravação. */
    private void assertRefused(String body, String expected) throws Exception {
        HttpResponse<String> response = post(bearer(1), body);

        String label = body.length() > 80 ? body.substring(0, 80) + "..." : body;
        assertEquals(400, response.statusCode(), label);
        assertEquals(expected, response.body(), label);
        assertTrue(app.blockchain.submissions.isEmpty(), label);
        assertEquals(0, app.transactions.saveCalls, label);
    }

    private void assertTokenInvalid(String authorization) throws Exception {
        HttpResponse<String> response = post(authorization, "{\"destinationAddress\":\"" + DESTINATION
                + "\",\"amountEth\":0.01}");

        assertEquals(401, response.statusCode(), authorization);
        assertEquals(TOKEN_INVALID, response.body(), authorization);
    }

    // ---------- 202 ----------

    @Test
    void returns202WithExactBodyAndSavesThePendingRow() throws Exception {
        HttpResponse<String> response = send("0.01");

        assertEquals(202, response.statusCode());
        assertEquals(ACCEPTED, response.body());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals(1, app.transactions.saved.size());
        EthTransaction saved = app.transactions.saved.get(0);
        assertEquals(TASK_ID, saved.getTaskId());
        assertEquals(1L, saved.getUserId());
        assertEquals(DESTINATION, saved.getDestinationAddress());
        assertEquals(new BigDecimal("0.01"), saved.getAmountEth());
        assertEquals(TestApp.NOW, saved.getCreatedAt());
        assertEquals(TestApp.NOW, saved.getUpdatedAt());
    }

    @Test
    void sourceAddressUserIdAndOtherExtraFieldsAreIgnored() throws Exception {
        String other = FakeBlockchainClient.address(99);

        HttpResponse<String> response = post(bearer(1), "{\"sourceAddress\":\"" + other + "\",\"userId\":2,"
                + "\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":0.01,\"extra\":[1,{\"a\":true}]}");

        assertEquals(202, response.statusCode());
        assertEquals(List.of(new FakeBlockchainClient.Submission(ADDRESS, DESTINATION, new BigDecimal("0.01"))),
                app.blockchain.submissions);
        assertEquals(1L, app.transactions.saved.get(0).getUserId());
    }

    @Test
    void amountKeepsAllEighteenDecimalPlaces() throws Exception {
        assertEquals(202, send("0.123456789012345678").statusCode());
        assertEquals(202, send("0.000000000000000001").statusCode());

        assertEquals("0.123456789012345678", app.blockchain.submissions.get(0).amountEth().toPlainString());
        assertEquals("0.000000000000000001", app.blockchain.submissions.get(1).amountEth().toPlainString());
        assertEquals(new BigDecimal("0.123456789012345678"), app.transactions.saved.get(0).getAmountEth());
    }

    @Test
    void trailingZerosAreAcceptedAndNormalized() throws Exception {
        assertEquals(202, send("0.0100000000000000000000").statusCode());
        assertEquals(202, send("1E+2").statusCode());

        assertEquals(new BigDecimal("0.01"), app.transactions.saved.get(0).getAmountEth());
        assertEquals(new BigDecimal("100"), app.transactions.saved.get(1).getAmountEth());
    }

    // ---------- amountEth em texto (aceito; nota do CT9) ----------

    @Test
    void amountAsNumericTextIsAccepted() throws Exception {
        HttpResponse<String> response = send("\"0.02\"");
        HttpResponse<String> withSpaces = send("\" 0.123456789012345678 \"");

        assertEquals(202, response.statusCode());
        assertEquals(202, withSpaces.statusCode());
        assertEquals(new BigDecimal("0.02"), app.blockchain.submissions.get(0).amountEth());
        assertEquals(new BigDecimal("0.123456789012345678"), app.transactions.saved.get(1).getAmountEth());
    }

    @Test
    void emptyTextAmountIsAmountInvalidAndNonNumericTextIsRequestInvalid() throws Exception {
        String prefix = "{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":";

        assertRefused(prefix + "\"\"}", AMOUNT_INVALID);
        assertRefused(prefix + "\"1E+999999999\"}", AMOUNT_INVALID);
        assertRefused(prefix + "\"-1\"}", AMOUNT_INVALID);
        assertRefused(prefix + "\"abc\"}", REQUEST_INVALID);
        assertRefused(prefix + "\"NaN\"}", REQUEST_INVALID);
        assertRefused(prefix + "\"0,01\"}", REQUEST_INVALID);
        assertRefused(prefix + "\"" + "1".repeat(1001) + "\"}", REQUEST_INVALID);
    }

    // ---------- 400 REQUEST_INVALID ----------

    @Test
    void emptyMalformedOrNonObjectBodyIsRequestInvalid() throws Exception {
        for (String body : List.of("", "   ", "{", "isto não é JSON", "null", "[]", "\"x\"", "1",
                "[{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":0.01}]")) {
            assertRefused(body, REQUEST_INVALID);
        }
    }

    @Test
    void fieldsWithTheWrongTypeAreRequestInvalid() throws Exception {
        String amount = ",\"amountEth\":0.01}";
        String destination = "{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":";

        assertRefused("{\"destinationAddress\":123" + amount, REQUEST_INVALID);
        assertRefused("{\"destinationAddress\":true" + amount, REQUEST_INVALID);
        assertRefused("{\"destinationAddress\":[\"" + DESTINATION + "\"]" + amount, REQUEST_INVALID);
        assertRefused("{\"destinationAddress\":{\"a\":1}" + amount, REQUEST_INVALID);
        assertRefused(destination + "true}", REQUEST_INVALID);
        assertRefused(destination + "{\"valor\":1}}", REQUEST_INVALID);
        assertRefused(destination + "[0.01]}", REQUEST_INVALID);
        assertRefused(destination + "[]}", REQUEST_INVALID);
    }

    @Test
    void numberWithMoreThan1000DigitsIsRequestInvalidNot500() throws Exception {
        String prefix = "{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":";

        assertRefused(prefix + "1".repeat(1001) + "}", REQUEST_INVALID);
        assertRefused(prefix + "0." + "1".repeat(1001) + "}", REQUEST_INVALID);
        assertRefused(prefix + "1E+9999999999}", REQUEST_INVALID);
    }

    // ---------- 400 ADDRESS_INVALID e AMOUNT_INVALID ----------

    @Test
    void missingOrNullFieldsGetTheirOwnCode() throws Exception {
        assertRefused("{}", ADDRESS_INVALID);
        assertRefused("{\"amountEth\":0.01}", ADDRESS_INVALID);
        assertRefused("{\"destinationAddress\":null,\"amountEth\":0.01}", ADDRESS_INVALID);
        assertRefused("{\"destinationAddress\":\"" + DESTINATION + "\"}", AMOUNT_INVALID);
        assertRefused("{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":null}", AMOUNT_INVALID);
    }

    @Test
    void invalidAddressOrAmountIsRefusedLocally() throws Exception {
        assertRefused("{\"destinationAddress\":\"0x123\",\"amountEth\":0.01}", ADDRESS_INVALID);
        assertRefused("{\"destinationAddress\":\"0x123\",\"amountEth\":-1}", ADDRESS_INVALID); // endereço primeiro
        for (String amount : List.of("0", "-1", "0.0000000000000000001", "123456789012345678901")) {
            assertRefused("{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":" + amount + "}",
                    AMOUNT_INVALID);
        }
    }

    @Test
    void extremeExponentsAreAmountInvalidQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String amount : List.of("1E+999999999", "1E-999999999", "1E+2147483647", "100E+2147483647",
                    "-1E+2147483647", "0E+2147483647", "1E-2147483647")) {
                assertRefused("{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":" + amount + "}",
                        AMOUNT_INVALID);
            }
        });
    }

    @Test
    void rejectionsFromTheBlockchainServiceAreRepassed() throws Exception {
        app.blockchain.rejectRequestSubmissionWith = "ADDRESS_INVALID";
        HttpResponse<String> address = send("0.01");
        app.blockchain.rejectRequestSubmissionWith = "AMOUNT_INVALID";
        HttpResponse<String> amount = send("0.01");

        assertEquals(400, address.statusCode());
        assertEquals(ADDRESS_INVALID, address.body());
        assertEquals(400, amount.statusCode());
        assertEquals(AMOUNT_INVALID, amount.body());
        assertEquals(0, app.transactions.saveCalls);
    }

    // ---------- 401 TOKEN_INVALID ----------

    // A rota não está em Main.PUBLIC_ROUTES (MainRoutesTest): o 401 vem da proteção por padrão.

    @Test
    void withoutTokenWrongSchemeGarbageOrExpiredIs401() throws Exception {
        JwtService earlier = new JwtService(TestApp.JWT_SECRET, TestApp.JWT_TTL_MINUTES,
                Clock.fixed(TestApp.NOW.minus(Duration.ofHours(5)), ZoneOffset.UTC));

        assertTokenInvalid(null);
        assertTokenInvalid("Basic " + app.jwtService.issue(1).token());
        assertTokenInvalid("Bearer lixo");
        assertTokenInvalid("Bearer " + earlier.issue(1).token());
        assertTrue(app.blockchain.submissions.isEmpty());
        assertEquals(0, app.transactions.saveCalls);
    }

    @Test
    void validTokenOfAUserThatNoLongerExistsIs401WithoutCt10() throws Exception {
        assertTokenInvalid(bearer(99));
        assertTrue(app.blockchain.submissions.isEmpty());
        assertEquals(0, app.transactions.saveCalls);
    }

    // ---------- 502, 500 e 405 ----------

    @Test
    void blockchainServiceFailureIs502AndNothingIsSaved() throws Exception {
        app.blockchain.failRequestSubmission = true;

        HttpResponse<String> response = send("0.01");

        assertEquals(502, response.statusCode());
        assertEquals(BLOCKCHAIN_UNAVAILABLE, response.body());
        assertEquals(0, app.transactions.saveCalls);
    }

    @Test
    void saveFailureAfterCt10Is500() throws Exception {
        app.transactions.failOnSave = new IllegalStateException("detalhe-do-banco");

        HttpResponse<String> response = send("0.01");

        assertEquals(500, response.statusCode());
        assertEquals("{\"code\":\"INTERNAL_ERROR\",\"message\":\"Erro interno.\"}", response.body());
    }

    @Test
    void getIs405() throws Exception {
        HttpResponse<String> response = app.send(app.request("/transactions")
                .header("Authorization", bearer(1)).GET().build());

        assertEquals(405, response.statusCode());
    }

    // ---------- CORS ----------

    @Test
    void allowedOriginGetsTheCorsHeaderOn202On400On401AndOn502() throws Exception {
        String valid = "{\"destinationAddress\":\"" + DESTINATION + "\",\"amountEth\":0.01}";
        HttpResponse<String> accepted = post(bearer(1), valid, TestApp.CORS_ORIGIN);
        HttpResponse<String> badRequest = post(bearer(1), "{", TestApp.CORS_ORIGIN);
        HttpResponse<String> unauthorized = post(null, valid, TestApp.CORS_ORIGIN);
        app.blockchain.failRequestSubmission = true;
        HttpResponse<String> badGateway = post(bearer(1), valid, TestApp.CORS_ORIGIN);

        assertEquals(202, accepted.statusCode());
        assertEquals(400, badRequest.statusCode());
        assertEquals(401, unauthorized.statusCode());
        assertEquals(502, badGateway.statusCode());
        for (HttpResponse<String> response : List.of(accepted, badRequest, unauthorized, badGateway)) {
            assertEquals(TestApp.CORS_ORIGIN,
                    response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        }
    }
}
