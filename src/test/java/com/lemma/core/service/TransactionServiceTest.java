package com.lemma.core.service;

import com.lemma.core.LogCapture;
import com.lemma.core.client.TaskState;
import com.lemma.core.dto.SendTransactionRequest;
import com.lemma.core.dto.SendTransactionResponse;
import com.lemma.core.dto.TransactionResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;
import com.lemma.core.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TransactionService com repositórios em memória, Blockchain Service falso e
 * relógio fixo, sem rede nem banco. Dois usuários gravados: o 1 (ana) e o 2
 * (bia), cada um com a sua carteira.
 */
class TransactionServiceTest {

    /** Com nanossegundos, para provar o corte no milissegundo (DATETIME(3)). */
    private static final Instant NOW = Instant.parse("2026-10-20T14:35:00.123456789Z");
    private static final Instant NOW_MILLIS = Instant.parse("2026-10-20T14:35:00.123Z");

    private static final String ANA_ADDRESS = FakeBlockchainClient.address(1);
    private static final String BIA_ADDRESS = FakeBlockchainClient.address(2);
    private static final String DESTINATION = "0x5B38Da6a701c568545dCfcB03FcB875f56beddC4";

    private static final String ADDRESS_MESSAGE = "Informe um endereço Ethereum válido.";
    private static final String AMOUNT_MESSAGE =
            "Informe um valor maior que zero, com no máximo 18 casas decimais e 20 dígitos inteiros.";

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final InMemoryEthTransactionRepository transactions = new InMemoryEthTransactionRepository();
    private final FakeBlockchainClient blockchain = new FakeBlockchainClient();
    private final TransactionService service = new TransactionService(users, transactions, blockchain,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void saveUsers() {
        users.save(new User("ana@example.com", "hash-falso-1", ANA_ADDRESS, NOW_MILLIS));
        users.save(new User("bia@example.com", "hash-falso-2", BIA_ADDRESS, NOW_MILLIS));
    }

    private static SendTransactionRequest request(String destination, String amount) {
        return new SendTransactionRequest(destination, amount == null ? null : new BigDecimal(amount));
    }

    private SendTransactionResponse send(long userId, String destination, String amount) {
        return service.send(userId, request(destination, amount));
    }

    private ApiException assertRefused(long userId, String destination, String amount,
                                       int status, String code, String message) {
        ApiException e = assertThrows(ApiException.class, () -> send(userId, destination, amount),
                destination + " " + amount);
        assertEquals(status, e.getStatus(), destination + " " + amount);
        assertEquals(code, e.getCode(), destination + " " + amount);
        assertEquals(message, e.getMessage(), destination + " " + amount);
        return e;
    }

    /**
     * Recusa na validação: sem ler o banco (o findById armado lançaria), sem chamar
     * o CT10, sem gravar e sem log.
     */
    private void assertRefusedBeforeAnything(String destination, String amount, String code, String message) {
        users.failOnFindById = new IllegalStateException("o banco não devia ser lido");
        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            assertRefused(1, destination, amount, 400, code, message);
            assertEquals(0, logs.lines().size(), destination + " " + amount);
        }
        assertTrue(blockchain.submissions.isEmpty(), destination + " " + amount);
        assertEquals(0, transactions.saveCalls, destination + " " + amount);
    }

    // ---------- sucesso ----------

    @Test
    void happyPathSavesPendingAndReturnsTheTaskIdFromCt10() {
        SendTransactionResponse response = send(1, DESTINATION, "0.01");

        String taskId = FakeBlockchainClient.taskId(1);
        assertEquals(new SendTransactionResponse(taskId, "PENDING"), response);
        assertEquals(1, transactions.saved.size());
        EthTransaction saved = transactions.saved.get(0);
        assertEquals(taskId, saved.getTaskId());
        assertEquals(1L, saved.getUserId());
        assertEquals(DESTINATION, saved.getDestinationAddress());
        assertEquals(new BigDecimal("0.01"), saved.getAmountEth());
        assertEquals(TransactionStatus.PENDING, saved.getStatus());
        assertNull(saved.getTransactionHash());
        assertNull(saved.getFailureReason());
        assertEquals(NOW_MILLIS, saved.getCreatedAt());
        assertEquals(NOW_MILLIS, saved.getUpdatedAt());
    }

    @Test
    void ct10ReceivesTheAddressOfTheTokenUserOnly() {
        send(1, DESTINATION, "0.01");
        send(2, DESTINATION, "0.02");

        assertEquals(List.of(
                new FakeBlockchainClient.Submission(ANA_ADDRESS, DESTINATION, new BigDecimal("0.01")),
                new FakeBlockchainClient.Submission(BIA_ADDRESS, DESTINATION, new BigDecimal("0.02"))),
                blockchain.submissions);
        assertEquals(2L, transactions.saved.get(1).getUserId());
        assertEquals(FakeBlockchainClient.taskId(2), transactions.saved.get(1).getTaskId());
    }

    @Test
    void destinationIsKeptAsSent() {
        String lower = DESTINATION.toLowerCase();
        String upper = "0x" + DESTINATION.substring(2).toUpperCase();

        send(1, lower, "0.01");
        send(1, upper, "0.01");

        assertEquals(lower, blockchain.submissions.get(0).destinationAddress());
        assertEquals(lower, transactions.saved.get(0).getDestinationAddress());
        assertEquals(upper, blockchain.submissions.get(1).destinationAddress());
        assertEquals(upper, transactions.saved.get(1).getDestinationAddress());
    }

    @Test
    void amountIsNormalizedBeforeCt10AndTheDatabase() {
        record Case(String sent, String normalized) {
        }
        List<Case> cases = List.of(
                new Case("0.0100000000000000000000", "0.01"),   // 22 casas, zeros à direita não contam
                new Case("1E+2", "100"),
                new Case("100", "100"),
                new Case("1.000000000000000000000000", "1"),
                new Case("0.000000000000000001", "0.000000000000000001"),
                new Case("1E-18", "0.000000000000000001"),
                new Case("0.123456789012345678", "0.123456789012345678"),
                new Case("12345678901234567890", "12345678901234567890"),          // 20 dígitos inteiros
                new Case("1E+19", "10000000000000000000"),
                new Case("99999999999999999999.999999999999999999", "99999999999999999999.999999999999999999"));

        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            send(1, DESTINATION, c.sent());

            BigDecimal sentToCt10 = blockchain.submissions.get(i).amountEth();
            BigDecimal saved = transactions.saved.get(i).getAmountEth();
            // BigDecimal.equals compara também a escala.
            assertEquals(new BigDecimal(c.normalized()), sentToCt10, c.sent());
            assertEquals(new BigDecimal(c.normalized()), saved, c.sent());
            assertEquals(c.normalized(), sentToCt10.toPlainString(), c.sent());
        }
    }

    @Test
    void successIsNotLogged() {
        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            send(1, DESTINATION, "0.01");

            assertEquals(0, logs.lines().size());
        }
    }

    // ---------- validação: endereço ----------

    @Test
    void invalidDestinationIsAddressInvalidBeforeAnything() {
        String hex40 = DESTINATION.substring(2);
        for (String destination : Arrays.asList(null, "", " ", "0x123", "0x" + hex40.substring(1),
                "0x" + hex40 + "a", hex40 + "00", "0X" + hex40, "0x" + hex40.replace('B', 'G'),
                " " + DESTINATION, DESTINATION + " ", DESTINATION + "\n", "0x" + hex40.substring(0, 39) + "é")) {
            assertRefusedBeforeAnything(destination, "0.01", "ADDRESS_INVALID", ADDRESS_MESSAGE);
        }
    }

    @Test
    void invalidAddressWinsOverInvalidAmount() {
        assertRefusedBeforeAnything("0x123", "-1", "ADDRESS_INVALID", ADDRESS_MESSAGE);
        assertRefusedBeforeAnything(null, null, "ADDRESS_INVALID", ADDRESS_MESSAGE);
        assertRefusedBeforeAnything("", "1E+999999999", "ADDRESS_INVALID", ADDRESS_MESSAGE);
    }

    // ---------- validação: valor ----------

    @Test
    void invalidAmountIsAmountInvalidBeforeAnything() {
        for (String amount : Arrays.asList(null, "0", "0.000", "0E-18", "-1", "-0.000000000000000001",
                "0.0000000000000000001",            // 19 casas: menos que 1 wei
                "1E-19",
                "1.0000000000000000001",
                "123456789012345678901",            // 21 dígitos inteiros
                "100000000000000000000",
                "1E+20",
                "99999999999999999999.9999999999999999999")) {
            assertRefusedBeforeAnything(DESTINATION, amount, "AMOUNT_INVALID", AMOUNT_MESSAGE);
        }
    }

    @Test
    void extremeExponentsAreAmountInvalidQuicklyAndWithoutArithmeticException() {
        // Rodam com tempo limite: 1E+999999999 em notação plana seria um texto de 1 GB.
        // 1E+2147483647 estoura a conta de dígitos em int; 100E+2147483647 faz o
        // stripTrailingZeros lançar ArithmeticException; zero e negativo são recusados
        // antes de qualquer conta.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (String amount : List.of("1E+999999999", "1E-999999999", "1E+2147483647", "100E+2147483647",
                    "1000E+2147483646", "1E-2147483647", "100E-2147483647", "-1E+2147483647",
                    "0E+2147483647", "0E-2147483647", "-1E-2147483647")) {
                assertRefusedBeforeAnything(DESTINATION, amount, "AMOUNT_INVALID", AMOUNT_MESSAGE);
            }
        });
    }

    // ---------- usuário do token ----------

    @Test
    void unknownUserIs401TokenInvalidWithoutCallingCt10() {
        ApiException e = assertRefused(99, DESTINATION, "0.01", 401, "TOKEN_INVALID",
                "Token ausente, inválido ou expirado.");

        assertEquals(ApiException.tokenInvalid().getMessage(), e.getMessage());
        assertTrue(blockchain.submissions.isEmpty());
        assertEquals(0, transactions.saveCalls);
    }

    @Test
    void databaseFailureReadingTheUserIs500AndOnlyTheClassIsLogged() {
        users.failOnFindById = new IllegalStateException("jdbc:mysql://core-db ana@example.com detalhe-do-banco");

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            assertRefused(1, DESTINATION, "0.01", 500, "INTERNAL_ERROR", "Erro interno.");

            assertEquals("WARN Envio: falha ao consultar o banco (IllegalStateException)", logs.all());
        }
        assertTrue(blockchain.submissions.isEmpty());
        assertEquals(0, transactions.saveCalls);
    }

    // ---------- CT10 ----------

    @Test
    void rejectionsFromCt10AreRepassedWithTheCoreApiMessageAndNothingIsSaved() {
        record Case(String code, String message) {
        }
        for (Case c : List.of(new Case("ADDRESS_INVALID", ADDRESS_MESSAGE), new Case("AMOUNT_INVALID", AMOUNT_MESSAGE))) {
            blockchain.rejectRequestSubmissionWith = c.code();

            try (LogCapture logs = new LogCapture(TransactionService.class)) {
                assertRefused(1, DESTINATION, "0.01", 400, c.code(), c.message());

                assertEquals(0, logs.lines().size()); // o cliente já logou
            }
        }
        assertEquals(2, blockchain.submissions.size()); // uma chamada por envio, sem retentativa
        assertEquals(0, transactions.saveCalls);
    }

    @Test
    void ct10FailureIs502AndNothingIsSaved() {
        blockchain.failRequestSubmission = true;

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            assertRefused(1, DESTINATION, "0.01", 502, "BLOCKCHAIN_UNAVAILABLE",
                    "Não foi possível enviar a transação agora. Tente novamente.");

            assertEquals(0, logs.lines().size()); // o cliente já logou
        }
        assertEquals(1, blockchain.submissions.size()); // sem retentativa
        assertEquals(0, transactions.saveCalls);
    }

    // ---------- falha ao gravar depois do CT10 ----------

    @Test
    void saveFailureAfterCt10Is500AndLogsTheTaskIdAndTheClassOnly() {
        transactions.failOnSave = new IllegalStateException(
                "Duplicate entry jdbc:mysql://core-db " + DESTINATION + " detalhe-do-banco");

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            ApiException e = assertRefused(1, DESTINATION, "0.01", 500, "INTERNAL_ERROR", "Erro interno.");

            assertEquals("WARN Task órfã " + FakeBlockchainClient.taskId(1)
                    + ": falha ao gravar a transação (IllegalStateException)", logs.all());
            assertFalse(e.getMessage().contains("detalhe-do-banco"));
        }
        assertEquals(1, blockchain.submissions.size()); // sem retentativa do CT10
        assertEquals(1, transactions.saveCalls);
        assertTrue(transactions.saved.isEmpty());
    }

    // ==================== CT13 getTransaction ====================

    private static final String TASK = "a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b";
    private static final String HASH = "0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060";
    /** Criação da linha, antes do relógio do service (NOW). */
    private static final Instant CREATED = Instant.parse("2026-10-20T14:30:00.456Z");
    private static final String NOT_FOUND_MESSAGE = "Transação não encontrada.";

    /** Linha PENDING de ana (usuário 1), com o valor na escala 18, como o banco devolve. */
    private void saveRow(String taskId, String amount) {
        transactions.save(new EthTransaction(taskId, 1, DESTINATION, new BigDecimal(amount).setScale(18), CREATED));
    }

    private void saveRow() {
        saveRow(TASK, "0.01");
    }

    private static TransactionResponse response(String status, String hash, String reason, String updatedAt) {
        return new TransactionResponse(TASK, status, DESTINATION, new BigDecimal("0.01"), hash, reason, updatedAt);
    }

    private ApiException assertGetRefused(long userId, String taskId, int status, String code, String message) {
        ApiException e = assertThrows(ApiException.class, () -> service.get(userId, taskId), taskId);
        assertEquals(status, e.getStatus(), taskId);
        assertEquals(code, e.getCode(), taskId);
        assertEquals(message, e.getMessage(), taskId);
        return e;
    }

    // ---------- leitura ----------

    @Test
    void finalStatesAreReturnedWithoutCallingCt14() {
        saveRow();
        transactions.setState(TASK, TransactionStatus.CONFIRMED, HASH, null, CREATED);

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            assertEquals(response("CONFIRMED", HASH, null, "2026-10-20T14:30:00Z"), service.get(1, TASK));

            transactions.setState(TASK, TransactionStatus.FAILED, null, FailureReason.REJECTED_BY_NETWORK, CREATED);
            assertEquals(response("FAILED", null, "REJECTED_BY_NETWORK", "2026-10-20T14:30:00Z"),
                    service.get(1, TASK));

            assertEquals(0, logs.lines().size());
        }
        assertTrue(blockchain.taskQueries.isEmpty());
        assertEquals(0, transactions.updateCalls);
    }

    @Test
    void otherUsersAndUnknownTransactionsGetTheSame404() {
        saveRow();

        ApiException otherUser = assertGetRefused(2, TASK, 404, "TRANSACTION_NOT_FOUND", NOT_FOUND_MESSAGE);
        ApiException unknown = assertGetRefused(1, "b4e2d3c5-6c7f-4a81-9ba2-1d3e4f5a6b7c", 404,
                "TRANSACTION_NOT_FOUND", NOT_FOUND_MESSAGE);

        assertEquals(otherUser.getMessage(), unknown.getMessage());
        assertTrue(blockchain.taskQueries.isEmpty());
    }

    @Test
    void malformedTaskIdIs404WithoutReadingTheDatabase() {
        transactions.failOnFind = new IllegalStateException("o banco não devia ser lido");

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            for (String taskId : Arrays.asList(null, "", "abc", TASK + "0", TASK.substring(1),
                    TASK.substring(1) + "/", TASK.substring(1) + "g", "../../wallets/aaaaaaaaaaaaaaaaaaaaa")) {
                assertGetRefused(1, taskId, 404, "TRANSACTION_NOT_FOUND", NOT_FOUND_MESSAGE);
            }
            assertEquals(0, logs.lines().size());
        }
        assertEquals(0, transactions.findCalls);
        assertTrue(blockchain.taskQueries.isEmpty());
    }

    @Test
    void uppercaseTaskIdFindsTheOwnRowAndCt14GetsItInLowercase() {
        saveRow();
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.PENDING, null, null));

        TransactionResponse response = service.get(1, TASK.toUpperCase());

        assertEquals(TASK, response.taskId());
        assertEquals(List.of(TASK), blockchain.taskQueries);
    }

    @Test
    void databaseFailureIs500AndLogsTheClassOnly() {
        saveRow();
        transactions.failOnFind = new IllegalStateException("jdbc:mysql://core-db " + TASK + " detalhe-do-banco");

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            ApiException e = assertGetRefused(1, TASK, 500, "INTERNAL_ERROR", "Erro interno.");

            assertEquals("WARN Acompanhamento: falha ao consultar o banco (IllegalStateException)", logs.all());
            assertFalse(e.getMessage().contains("detalhe-do-banco"));
        }
        assertTrue(blockchain.taskQueries.isEmpty());
    }

    // ---------- PENDING: consulta ativa (CT14) ----------

    @Test
    void pendingWithCt14ConfirmedSavesAndReturnsTheSavedRow() {
        saveRow();
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.CONFIRMED, HASH, null));

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            assertEquals(response("CONFIRMED", HASH, null, "2026-10-20T14:35:00Z"), service.get(1, TASK));
            assertEquals(0, logs.lines().size());
        }
        EthTransaction row = transactions.row(TASK);
        assertEquals(TransactionStatus.CONFIRMED, row.getStatus());
        assertEquals(HASH, row.getTransactionHash());
        assertNull(row.getFailureReason());
        assertEquals(NOW_MILLIS, row.getUpdatedAt());
        assertEquals(CREATED, row.getCreatedAt());
        assertEquals(1, transactions.updateCalls);
    }

    @Test
    void pendingWithCt14FailedWithoutHashSavesAndReturns() {
        saveRow();
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.FAILED, null, FailureReason.REJECTED_BY_NETWORK));

        assertEquals(response("FAILED", null, "REJECTED_BY_NETWORK", "2026-10-20T14:35:00Z"), service.get(1, TASK));

        EthTransaction row = transactions.row(TASK);
        assertEquals(TransactionStatus.FAILED, row.getStatus());
        assertNull(row.getTransactionHash());
        assertEquals(FailureReason.REJECTED_BY_NETWORK, row.getFailureReason());
        assertEquals(NOW_MILLIS, row.getUpdatedAt());
    }

    @Test
    void hashThatAppearsWhileStillPendingIsSaved() {
        saveRow();
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.PENDING, HASH, null));

        assertEquals(response("PENDING", HASH, null, "2026-10-20T14:35:00Z"), service.get(1, TASK));

        assertEquals(HASH, transactions.row(TASK).getTransactionHash());
        assertEquals(TransactionStatus.PENDING, transactions.row(TASK).getStatus());
        assertEquals(1, transactions.updateCalls);
    }

    @Test
    void hashThatDisappearsWhileStillPendingIsCleared() {
        saveRow();
        transactions.setState(TASK, TransactionStatus.PENDING, HASH, null, CREATED);
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.PENDING, null, null));

        assertEquals(response("PENDING", null, null, "2026-10-20T14:35:00Z"), service.get(1, TASK));

        assertNull(transactions.row(TASK).getTransactionHash());
    }

    @Test
    void sameStateFromCt14WritesNothing() {
        saveRow();
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.PENDING, null, null));

        assertEquals(response("PENDING", null, null, "2026-10-20T14:30:00Z"), service.get(1, TASK));
        assertEquals(response("PENDING", null, null, "2026-10-20T14:30:00Z"), service.get(1, TASK));

        // Com o hash já gravado, o mesmo hash também não é novidade.
        transactions.setState(TASK, TransactionStatus.PENDING, HASH, null, CREATED);
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.PENDING, HASH, null));
        assertEquals(response("PENDING", HASH, null, "2026-10-20T14:30:00Z"), service.get(1, TASK));

        assertEquals(0, transactions.updateCalls);
        assertEquals(CREATED, transactions.row(TASK).getUpdatedAt());
        assertEquals(3, blockchain.taskQueries.size());
    }

    @Test
    void ct14FailureReturnsTheLocalStateWithoutErrorAndWithoutLog() {
        saveRow();
        blockchain.failGetTask = true;

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            assertEquals(response("PENDING", null, null, "2026-10-20T14:30:00Z"), service.get(1, TASK));
            assertEquals(0, logs.lines().size()); // o cliente já logou
        }
        assertEquals(0, transactions.updateCalls);
        assertEquals(List.of(TASK), blockchain.taskQueries);
    }

    @Test
    void ct14UnknownTaskReturnsTheLocalState() {
        saveRow(); // o falso não conhece a task: como o 404 TASK_NOT_FOUND

        assertEquals(response("PENDING", null, null, "2026-10-20T14:30:00Z"), service.get(1, TASK));
        assertEquals(0, transactions.updateCalls);
    }

    @Test
    void finalStateNeverRegressesWhenTheWebhookWinsDuringCt14() {
        saveRow();
        // O webhook grava CONFIRMED enquanto o CT14 está em andamento; o CT14 traz FAILED.
        blockchain.onGetTask = () -> transactions.setState(TASK, TransactionStatus.CONFIRMED, HASH, null,
                CREATED.plusSeconds(5));
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.FAILED, null, FailureReason.INTERNAL_ERROR));

        assertEquals(response("CONFIRMED", HASH, null, "2026-10-20T14:30:05Z"), service.get(1, TASK));

        EthTransaction row = transactions.row(TASK);
        assertEquals(TransactionStatus.CONFIRMED, row.getStatus());
        assertEquals(HASH, row.getTransactionHash());
        assertNull(row.getFailureReason());
        assertEquals(1, transactions.updateCalls); // tentou, e a condição PENDING não deixou
    }

    @Test
    void updateFailureIs500AndLogsTheClassOnly() {
        saveRow();
        blockchain.tasks.put(TASK, new TaskState(TransactionStatus.CONFIRMED, HASH, null));
        transactions.failOnUpdate = new IllegalStateException("jdbc:mysql://core-db detalhe-do-banco");

        try (LogCapture logs = new LogCapture(TransactionService.class)) {
            assertGetRefused(1, TASK, 500, "INTERNAL_ERROR", "Erro interno.");
            assertEquals("WARN Atualização da transação: falha no banco (IllegalStateException)", logs.all());
        }
    }

    @Test
    void applyTaskStateChangesOnlyPendingRows() {
        saveRow();

        assertTrue(service.applyTaskState(TASK, TransactionStatus.CONFIRMED, HASH, null));
        assertFalse(service.applyTaskState(TASK, TransactionStatus.FAILED, null, FailureReason.INTERNAL_ERROR));
        assertFalse(service.applyTaskState("b4e2d3c5-6c7f-4a81-9ba2-1d3e4f5a6b7c",
                TransactionStatus.CONFIRMED, HASH, null));

        assertEquals(TransactionStatus.CONFIRMED, transactions.row(TASK).getStatus());
        assertEquals(NOW_MILLIS, transactions.row(TASK).getUpdatedAt());
    }

    // ---------- formato da resposta ----------

    @Test
    void amountIsReturnedWithoutTrailingZerosAndWithoutExponent() {
        record Case(String taskId, String amount) {
        }
        List<Case> cases = List.of(
                new Case("00000000-0000-4000-8000-000000000001", "0.01"),
                new Case("00000000-0000-4000-8000-000000000002", "100"),
                new Case("00000000-0000-4000-8000-000000000003", "0.000000000000000001"),
                new Case("00000000-0000-4000-8000-000000000004", "12345678901234567890.123456789012345678"));

        for (Case c : cases) {
            saveRow(c.taskId(), c.amount());
            transactions.setState(c.taskId(), TransactionStatus.CONFIRMED, HASH, null, CREATED);

            BigDecimal amount = service.get(1, c.taskId()).amountEth();

            assertEquals(c.amount(), amount.toPlainString(), c.amount());
            assertTrue(amount.scale() >= 0, c.amount());
        }
    }

    @Test
    void optionalFieldsAreNullWhenAbsent() {
        saveRow();
        blockchain.failGetTask = true;

        TransactionResponse response = service.get(1, TASK);

        assertNull(response.transactionHash());
        assertNull(response.failureReason());
    }
}
