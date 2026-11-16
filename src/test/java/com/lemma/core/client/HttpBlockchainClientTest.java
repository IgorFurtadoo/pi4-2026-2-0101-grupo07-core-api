package com.lemma.core.client;

import com.lemma.core.LogCapture;
import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o CT4, o CT7, o CT10 e o CT14 contra um Blockchain Service falso (servidor HTTP do próprio
 * JDK), sem rede externa. O falso guarda a última requisição recebida e conta as
 * requisições.
 */
class HttpBlockchainClientTest {

    private static final String SERVICE_KEY = "chave-de-servico-de-teste";
    private static final String ADDRESS = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private HttpServer server;
    private ExecutorService serverThreads;

    private volatile String receivedMethod;
    private volatile String receivedPath;
    private volatile String receivedServiceKey;
    private volatile String receivedContentType;
    private volatile String receivedBody;
    private volatile String receivedRawPath;
    private volatile String receivedQuery;
    private final AtomicInteger requestCount = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            serverThreads.shutdownNow(); // interrompe o handler que estiver dormindo
        }
    }

    /** Sobe o falso, que espera delayMillis e responde status com o corpo dado. Devolve a base. */
    private String startServer(int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            receivedRawPath = exchange.getRequestURI().getRawPath();
            receivedQuery = exchange.getRequestURI().getRawQuery();
            receivedMethod = exchange.getRequestMethod();
            receivedPath = exchange.getRequestURI().getPath();
            receivedServiceKey = exchange.getRequestHeaders().getFirst("X-Service-Key");
            receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            try (InputStream in = exchange.getRequestBody()) {
                receivedBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String startServer(int status, String body) throws IOException {
        return startServer(status, body, 0);
    }

    private static HttpBlockchainClient client(String baseUrl) {
        return new HttpBlockchainClient(baseUrl, SERVICE_KEY, TIMEOUT);
    }

    private static void assertFails(String baseUrl) {
        assertThrows(BlockchainUnavailableException.class, () -> client(baseUrl).createWallet());
    }

    // ---------- sucesso ----------

    @Test
    void postsToWalletsWithServiceKeyAndNoBody() throws Exception {
        String base = startServer(201, "{\"ethereumAddress\":\"" + ADDRESS + "\"}");

        client(base).createWallet();

        assertEquals("POST", receivedMethod);
        assertEquals("/wallets", receivedPath);
        assertEquals(SERVICE_KEY, receivedServiceKey);
        assertEquals("", receivedBody);
    }

    @Test
    void returnsTheAddressFrom201() throws Exception {
        String base = startServer(201, "{\"ethereumAddress\":\"" + ADDRESS + "\"}");

        assertEquals(ADDRESS, client(base).createWallet());
    }

    // ---------- falhas ----------

    @Test
    void serviceKeyInvalidFailsAndIsLoggedAsError() throws Exception {
        String base = startServer(401, "{\"code\":\"SERVICE_KEY_INVALID\",\"message\":\"Chave inválida.\"}");

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertFails(base);

            assertEquals(1, logs.lines().size());
            String line = logs.lines().get(0);
            assertTrue(line.startsWith("ERROR CT4 createWallet: HTTP 401 SERVICE_KEY_INVALID"), line);
            assertFalse(line.contains(SERVICE_KEY));
            assertFalse(line.contains("127.0.0.1"));
            assertFalse(line.contains("Chave inválida"));
        }
    }

    @Test
    void serverErrorFailsAndIsLoggedAsWarnWithCode() throws Exception {
        String base = startServer(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Erro interno.\"}");

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertFails(base);

            assertEquals("WARN CT4 createWallet: HTTP 500 INTERNAL_ERROR", logs.all());
        }
    }

    @Test
    void codeOutsideTheExpectedFormatIsNotLogged() throws Exception {
        String base = startServer(502, "{\"code\":\"<html>texto qualquer</html>\"}");

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertFails(base);

            assertEquals("WARN CT4 createWallet: HTTP 502 sem code", logs.all());
        }
    }

    @Test
    void slowerThanTimeoutFailsInTime() throws Exception {
        String base = startServer(201, "{\"ethereumAddress\":\"" + ADDRESS + "\"}", 5_000);

        long start = System.nanoTime();
        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertFails(base);
            assertEquals("WARN CT4 createWallet: falha de rede (HttpTimeoutException)", logs.all());
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMillis < 3_000, "devia desistir perto de 1 s, levou " + elapsedMillis + " ms");
    }

    @Test
    void unreadableBodyFails() throws Exception {
        assertFails(startServer(201, "isto não é JSON"));
    }

    @Test
    void emptyBodyFails() throws Exception {
        assertFails(startServer(201, ""));
    }

    @Test
    void missingAddressFails() throws Exception {
        assertFails(startServer(201, "{\"outroCampo\":\"" + ADDRESS + "\"}"));
    }

    @Test
    void addressOutOfFormatFails() throws Exception {
        assertFails(startServer(201, "{\"ethereumAddress\":\"0x123\"}"));
        stopServer();
        assertFails(startServer(201, "{\"ethereumAddress\":123}"));
    }

    @Test
    void otherSuccessStatusFails() throws Exception {
        assertFails(startServer(200, "{\"ethereumAddress\":\"" + ADDRESS + "\"}"));
    }

    @Test
    void connectionRefusedFails() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort(); // ao sair do try, a porta fica fechada
        }

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertFails("http://127.0.0.1:" + closedPort);
            assertEquals("WARN CT4 createWallet: falha de rede (ConnectException)", logs.all());
        }
    }

    // ==================== CT7 getWalletBalance ====================

    private static String balanceBody(String balanceEthJson) {
        return "{\"ethereumAddress\":\"" + ADDRESS + "\",\"balanceEth\":" + balanceEthJson
                + ",\"network\":\"SEPOLIA\"}";
    }

    private static BalanceResult getBalance(String baseUrl) {
        return client(baseUrl).getBalance(ADDRESS);
    }

    private static void assertBalanceFails(String baseUrl) {
        assertThrows(BlockchainUnavailableException.class, () -> getBalance(baseUrl));
    }

    /** O corpo de 200 é recusado e o log diz só "fora do formato", sem o corpo. */
    private void assertBalanceBodyRefused(String body) throws Exception {
        String base = startServer(200, body);
        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertBalanceFails(base);
            assertEquals("WARN CT7 getWalletBalance: HTTP 200 com corpo fora do formato", logs.all(), body);
        }
        stopServer();
    }

    // ---------- sucesso ----------

    @Test
    void balanceIsAGetToTheWalletPathWithServiceKeyAndNoBody() throws Exception {
        String base = startServer(200, balanceBody("0.25"));

        getBalance(base);

        assertEquals("GET", receivedMethod);
        assertEquals("/wallets/" + ADDRESS + "/balance", receivedRawPath);
        assertNull(receivedQuery);
        assertEquals(SERVICE_KEY, receivedServiceKey);
        assertEquals("", receivedBody);
        assertEquals(1, requestCount.get());
    }

    @Test
    void balanceValuesComeBackExactly() throws Exception {
        // BigDecimal.equals compara também a escala: 0 e 0.000000000000000000 são diferentes.
        for (String value : List.of("0.000000000000000001", "0.25", "0", "0.000000000000000000",
                "123456789.123456789012345678")) {
            String base = startServer(200, balanceBody(value));

            BalanceResult result = getBalance(base);

            assertEquals(new BigDecimal(value), result.balanceEth(), value);
            assertEquals(value, result.balanceEth().toPlainString(), value);
            assertEquals("SEPOLIA", result.network(), value);
            stopServer();
        }
    }

    @Test
    void oneWeiWithExponentIsAcceptedAndWrittenPlain() throws Exception {
        String base = startServer(200, balanceBody("1E-18"));

        BalanceResult result = getBalance(base);

        assertEquals("0.000000000000000001", result.balanceEth().toPlainString());
    }

    @Test
    void successIsNotLogged() throws Exception {
        String base = startServer(200, balanceBody("0.25"));

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            getBalance(base);
            assertEquals(0, logs.lines().size());
        }
    }

    // ---------- corpo de 200 fora do formato ----------

    @Test
    void balanceEthMissingTextNullOrNegativeIsRefused() throws Exception {
        assertBalanceBodyRefused("{\"ethereumAddress\":\"" + ADDRESS + "\",\"network\":\"SEPOLIA\"}");
        assertBalanceBodyRefused(balanceBody("\"0.25\""));
        assertBalanceBodyRefused(balanceBody("null"));
        assertBalanceBodyRefused(balanceBody("-0.25"));
        assertBalanceBodyRefused(balanceBody("-0.000000000000000001"));
        assertBalanceBodyRefused(balanceBody("true"));
        assertBalanceBodyRefused(balanceBody("{\"valor\":1}"));
        assertBalanceBodyRefused(balanceBody("[0.25]"));
    }

    @Test
    void balanceEthWithScaleOutsideZeroTo18IsRefused() throws Exception {
        assertBalanceBodyRefused(balanceBody("0.0000000000000000001"));  // 19 casas: menos que 1 wei
        assertBalanceBodyRefused(balanceBody("1E-19"));
        assertBalanceBodyRefused(balanceBody("1E+999999999"));           // não seria serializável em notação plana
        assertBalanceBodyRefused(balanceBody("1E+2"));
    }

    @Test
    void networkMissingOrNotSepoliaIsRefused() throws Exception {
        assertBalanceBodyRefused("{\"ethereumAddress\":\"" + ADDRESS + "\",\"balanceEth\":0.25}");
        assertBalanceBodyRefused("{\"balanceEth\":0.25,\"network\":\"MAINNET\"}");
        assertBalanceBodyRefused("{\"balanceEth\":0.25,\"network\":\"sepolia\"}");
        assertBalanceBodyRefused("{\"balanceEth\":0.25,\"network\":null}");
        assertBalanceBodyRefused("{\"balanceEth\":0.25,\"network\":1}");
        assertBalanceBodyRefused("{\"balanceEth\":0.25,\"network\":[\"SEPOLIA\"]}");
    }

    @Test
    void rootThatIsNotAnObjectIsRefused() throws Exception {
        assertBalanceBodyRefused("");
        assertBalanceBodyRefused("[]");
        assertBalanceBodyRefused("123");
        assertBalanceBodyRefused("\"x\"");
        assertBalanceBodyRefused("null");
        assertBalanceBodyRefused("isto não é JSON");
        assertBalanceBodyRefused("{\"balanceEth\":0.25,");
    }

    // ---------- status de erro ----------

    @Test
    void errorStatusesFailAndOnlyTheCodeIsLogged() throws Exception {
        record Case(int status, String code, String expectedLog) {
        }
        List<Case> cases = List.of(
                new Case(400, "ADDRESS_INVALID",
                        "ERROR CT7 getWalletBalance: HTTP 400 ADDRESS_INVALID; erro de configuração ou de dado"),
                new Case(401, "SERVICE_KEY_INVALID", "ERROR CT7 getWalletBalance: HTTP 401 SERVICE_KEY_INVALID;"
                        + " a SERVICE_KEY difere da configurada no Blockchain Service"),
                new Case(404, "WALLET_NOT_FOUND",
                        "ERROR CT7 getWalletBalance: HTTP 404 WALLET_NOT_FOUND; erro de configuração ou de dado"),
                new Case(500, "INTERNAL_ERROR", "WARN CT7 getWalletBalance: HTTP 500 INTERNAL_ERROR"),
                new Case(502, "RPC_UNAVAILABLE", "WARN CT7 getWalletBalance: HTTP 502 RPC_UNAVAILABLE"));

        for (Case c : cases) {
            String base = startServer(c.status(),
                    "{\"code\":\"" + c.code() + "\",\"message\":\"mensagem-secreta-do-corpo\"}");
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                assertBalanceFails(base);

                String all = logs.all();
                assertEquals(c.expectedLog(), all);
                assertFalse(all.contains("mensagem-secreta-do-corpo"));
                assertFalse(all.contains(SERVICE_KEY));
                assertFalse(all.contains("127.0.0.1"));
                assertFalse(all.contains(ADDRESS));
            }
            stopServer();
        }
    }

    @Test
    void errorStatusWithoutReadableCodeLogsSemCode() throws Exception {
        String base = startServer(502, "<html>Bad Gateway</html>");

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertBalanceFails(base);
            assertEquals("WARN CT7 getWalletBalance: HTTP 502 sem code", logs.all());
        }
    }

    @Test
    void otherSuccessStatusIsRefused() throws Exception {
        assertBalanceFails(startServer(201, balanceBody("0.25")));
    }

    // ---------- rede ----------

    @Test
    void balanceSlowerThanTimeoutFailsInTime() throws Exception {
        String base = startServer(200, balanceBody("0.25"), 5_000);

        long start = System.nanoTime();
        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertBalanceFails(base);
            assertEquals("WARN CT7 getWalletBalance: falha de rede (HttpTimeoutException)", logs.all());
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMillis < 3_000, "devia desistir perto de 1 s, levou " + elapsedMillis + " ms");
    }

    @Test
    void balanceConnectionRefusedFails() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertBalanceFails("http://127.0.0.1:" + closedPort);
            assertEquals("WARN CT7 getWalletBalance: falha de rede (ConnectException)", logs.all());
        }
    }

    // ---------- endereço fora do formato: nenhuma chamada ----------

    @Test
    void addressOutOfFormatMakesNoCall() throws Exception {
        String base = startServer(200, balanceBody("0.25"));
        HttpBlockchainClient client = client(base);

        for (String address : Arrays.asList(null, "", "0x123", "../../tasks",
                "0x71C7656EC7ab88b098defB751B7401B5f6d8976F/../x",
                "0x71C7656EC7ab88b098defB751B7401B5f6d8976F?a=b",
                "0x71C7656EC7ab88b098defB751B7401B5f6d8976F\n",
                "0x71C7656EC7ab88b098defB751B7401B5f6d8976G",
                "71C7656EC7ab88b098defB751B7401B5f6d8976F00")) {
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                assertThrows(BlockchainUnavailableException.class, () -> client.getBalance(address), address);
                assertEquals("ERROR CT7 getWalletBalance: endereço da carteira fora do formato;"
                        + " a chamada não foi feita", logs.all(), address);
            }
        }

        assertEquals(0, requestCount.get());
    }

    // ==================== CT10 requestTransactionSubmission ====================

    private static final String DESTINATION = "0x5B38Da6a701c568545dCfcB03FcB875f56beddC4";
    private static final String TASK_ID = "a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b";
    /** Valor com marca própria, para provar que não vai ao log. */
    private static final BigDecimal AMOUNT = new BigDecimal("0.123456789012345678");

    private static String acceptedBody(String taskIdJson, String statusJson) {
        return "{\"taskId\":" + taskIdJson + ",\"status\":" + statusJson + "}";
    }

    private static final String ACCEPTED = acceptedBody("\"" + TASK_ID + "\"", "\"PENDING\"");

    private static String submit(String baseUrl) {
        return client(baseUrl).requestSubmission(ADDRESS, DESTINATION, AMOUNT);
    }

    private static void assertSubmissionUnavailable(String baseUrl) {
        assertThrows(BlockchainUnavailableException.class, () -> submit(baseUrl));
    }

    /** O log nunca traz corpo, chave, URL, endereços (nas duas caixas) nem valor. */
    private static void assertLogClean(String log) {
        String lower = log.toLowerCase();
        assertFalse(log.contains("mensagem-secreta-do-corpo"), log);
        assertFalse(log.contains(SERVICE_KEY), log);
        assertFalse(log.contains("127.0.0.1"), log);
        assertFalse(lower.contains(ADDRESS.toLowerCase().substring(2)), log);
        assertFalse(lower.contains(DESTINATION.toLowerCase().substring(2)), log);
        assertFalse(log.contains("123456789012345678"), log);
    }

    /** O 202 é recusado e o log diz só "fora do formato", sem o corpo. */
    private void assertAcceptedBodyRefused(String body) throws Exception {
        String base = startServer(202, body);
        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertSubmissionUnavailable(base);
            assertEquals("WARN CT10 requestSubmission: HTTP 202 com corpo fora do formato", logs.all(), body);
        }
        assertEquals(1, requestCount.getAndSet(0), body);
        stopServer();
    }

    // ---------- sucesso ----------

    @Test
    void submissionIsAPostToTransactionsWithServiceKeyJsonAndExactBody() throws Exception {
        String base = startServer(202, ACCEPTED);

        String taskId = submit(base);

        assertEquals(TASK_ID, taskId);
        assertEquals("POST", receivedMethod);
        assertEquals("/transactions", receivedRawPath);
        assertNull(receivedQuery);
        assertEquals(SERVICE_KEY, receivedServiceKey);
        assertEquals("application/json", receivedContentType);
        assertEquals("{\"sourceAddress\":\"" + ADDRESS + "\",\"destinationAddress\":\"" + DESTINATION
                + "\",\"amountEth\":0.123456789012345678}", receivedBody);
        assertEquals(1, requestCount.get());
    }

    @Test
    void amountIsWrittenAsAPlainJsonNumber() throws Exception {
        record Case(BigDecimal amount, String json) {
        }
        List<Case> cases = List.of(
                new Case(new BigDecimal("0.01"), "0.01"),
                new Case(new BigDecimal("1E-18"), "0.000000000000000001"),
                new Case(new BigDecimal("0.000000000000000001"), "0.000000000000000001"),
                new Case(new BigDecimal("100"), "100"),
                new Case(new BigDecimal("1E+2").setScale(0), "100"),
                new Case(new BigDecimal("99999999999999999999.999999999999999999"),
                        "99999999999999999999.999999999999999999"));

        for (Case c : cases) {
            String base = startServer(202, ACCEPTED);

            client(base).requestSubmission(ADDRESS, DESTINATION, c.amount());

            assertEquals("{\"sourceAddress\":\"" + ADDRESS + "\",\"destinationAddress\":\"" + DESTINATION
                    + "\",\"amountEth\":" + c.json() + "}", receivedBody, c.json());
            stopServer();
        }
    }

    @Test
    void uppercaseTaskIdIsAccepted() throws Exception {
        String upper = TASK_ID.toUpperCase();
        String base = startServer(202, acceptedBody("\"" + upper + "\"", "\"PENDING\""));

        assertEquals(upper, submit(base));
    }

    @Test
    void submissionSuccessIsNotLogged() throws Exception {
        String base = startServer(202, ACCEPTED);

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            submit(base);
            assertEquals(0, logs.lines().size());
        }
    }

    // ---------- 202 fora do formato ----------

    @Test
    void taskIdMissingNotTextOrOutOfFormatIsRefused() throws Exception {
        assertAcceptedBodyRefused("{\"status\":\"PENDING\"}");
        assertAcceptedBodyRefused(acceptedBody("null", "\"PENDING\""));
        assertAcceptedBodyRefused(acceptedBody("123", "\"PENDING\""));
        assertAcceptedBodyRefused(acceptedBody("[\"" + TASK_ID + "\"]", "\"PENDING\""));
        assertAcceptedBodyRefused(acceptedBody("\"\"", "\"PENDING\""));
        assertAcceptedBodyRefused(acceptedBody("\"tarefa-1\"", "\"PENDING\""));
        assertAcceptedBodyRefused(acceptedBody("\"" + TASK_ID + "a\"", "\"PENDING\""));       // 37
        assertAcceptedBodyRefused(acceptedBody("\"" + TASK_ID.substring(1) + "\"", "\"PENDING\""));  // 35
        assertAcceptedBodyRefused(acceptedBody("\"" + TASK_ID.replace('a', 'g') + "\"", "\"PENDING\""));
        assertAcceptedBodyRefused(acceptedBody("\"../../" + TASK_ID.substring(6) + "\"", "\"PENDING\""));
    }

    @Test
    void statusMissingOrNotPendingIsRefused() throws Exception {
        assertAcceptedBodyRefused("{\"taskId\":\"" + TASK_ID + "\"}");
        assertAcceptedBodyRefused(acceptedBody("\"" + TASK_ID + "\"", "null"));
        assertAcceptedBodyRefused(acceptedBody("\"" + TASK_ID + "\"", "\"CONFIRMED\""));
        assertAcceptedBodyRefused(acceptedBody("\"" + TASK_ID + "\"", "\"pending\""));
        assertAcceptedBodyRefused(acceptedBody("\"" + TASK_ID + "\"", "1"));
    }

    @Test
    void acceptedBodyThatIsNotAnObjectIsRefused() throws Exception {
        assertAcceptedBodyRefused("");
        assertAcceptedBodyRefused("[]");
        assertAcceptedBodyRefused("\"" + TASK_ID + "\"");
        assertAcceptedBodyRefused("null");
        assertAcceptedBodyRefused("isto não é JSON");
        assertAcceptedBodyRefused("{\"taskId\":\"" + TASK_ID + "\",");
    }

    @Test
    void otherSuccessStatusesAreRefused() throws Exception {
        for (int status : List.of(200, 201, 204)) {
            String base = startServer(status, status == 204 ? "" : ACCEPTED);
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                assertSubmissionUnavailable(base);
                assertEquals("WARN CT10 requestSubmission: HTTP " + status + " sem code", logs.all());
            }
            stopServer();
        }
    }

    // ---------- 400 repassado ----------

    @Test
    void addressAndAmountInvalidAreRepassedRejectionsLoggedAsWarn() throws Exception {
        for (String code : List.of("ADDRESS_INVALID", "AMOUNT_INVALID")) {
            String base = startServer(400, "{\"code\":\"" + code + "\",\"message\":\"mensagem-secreta-do-corpo\"}");
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                BlockchainRejectedException e = assertThrows(BlockchainRejectedException.class, () -> submit(base));

                assertEquals(code, e.getCode());
                assertFalse(e.getMessage().contains("mensagem-secreta-do-corpo"));
                assertEquals("WARN CT10 requestSubmission: HTTP 400 " + code, logs.all());
                assertLogClean(logs.all());
            }
            assertEquals(1, requestCount.getAndSet(0), code);
            stopServer();
        }
    }

    // ---------- demais erros: indisponível ----------

    @Test
    void otherErrorsAreUnavailableWithTheRightLogLevel() throws Exception {
        record Case(int status, String body, String expectedLog) {
        }
        String secret = ",\"message\":\"mensagem-secreta-do-corpo\"}";
        List<Case> cases = List.of(
                new Case(400, "{\"code\":\"REQUEST_INVALID\"" + secret,
                        "ERROR CT10 requestSubmission: HTTP 400 REQUEST_INVALID; erro de configuração ou de dado"),
                new Case(401, "{\"code\":\"SERVICE_KEY_INVALID\"" + secret,
                        "ERROR CT10 requestSubmission: HTTP 401 SERVICE_KEY_INVALID;"
                                + " a SERVICE_KEY difere da configurada no Blockchain Service"),
                new Case(404, "{\"code\":\"WALLET_NOT_FOUND\"" + secret,
                        "ERROR CT10 requestSubmission: HTTP 404 WALLET_NOT_FOUND; erro de configuração ou de dado"),
                new Case(400, "{\"code\":\"OUTRO_CODE\"" + secret, "WARN CT10 requestSubmission: HTTP 400 OUTRO_CODE"),
                new Case(400, "{\"code\":\"address_invalid\"" + secret, "WARN CT10 requestSubmission: HTTP 400 sem code"),
                new Case(400, "<html>Bad Request</html>", "WARN CT10 requestSubmission: HTTP 400 sem code"),
                // O code de recusa só é repassado com 400.
                new Case(404, "{\"code\":\"ADDRESS_INVALID\"" + secret, "WARN CT10 requestSubmission: HTTP 404 ADDRESS_INVALID"),
                new Case(409, "{\"code\":\"AMOUNT_INVALID\"" + secret, "WARN CT10 requestSubmission: HTTP 409 AMOUNT_INVALID"),
                new Case(404, "", "WARN CT10 requestSubmission: HTTP 404 sem code"),
                new Case(500, "{\"code\":\"INTERNAL_ERROR\"" + secret, "WARN CT10 requestSubmission: HTTP 500 INTERNAL_ERROR"),
                new Case(502, "<html>Bad Gateway</html>", "WARN CT10 requestSubmission: HTTP 502 sem code"));

        for (Case c : cases) {
            String base = startServer(c.status(), c.body());
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                assertSubmissionUnavailable(base);

                assertEquals(c.expectedLog(), logs.all(), c.body());
                assertLogClean(logs.all());
            }
            assertEquals(1, requestCount.getAndSet(0), c.body());
            stopServer();
        }
    }

    // ---------- rede: uma única tentativa ----------

    @Test
    void submissionSlowerThanTimeoutFailsInTimeWithASingleRequest() throws Exception {
        String base = startServer(202, ACCEPTED, 5_000);

        long start = System.nanoTime();
        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertSubmissionUnavailable(base);
            assertEquals("WARN CT10 requestSubmission: falha de rede (HttpTimeoutException)", logs.all());
            assertLogClean(logs.all());
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMillis < 3_000, "devia desistir perto de 1 s, levou " + elapsedMillis + " ms");
        assertEquals(1, requestCount.get());
    }

    @Test
    void submissionConnectionRefusedFails() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertSubmissionUnavailable("http://127.0.0.1:" + closedPort);
            assertEquals("WARN CT10 requestSubmission: falha de rede (ConnectException)", logs.all());
        }
    }

    @Test
    void serverErrorIsNotRetried() throws Exception {
        String base = startServer(500, "{\"code\":\"INTERNAL_ERROR\"}");

        assertSubmissionUnavailable(base);
        Thread.sleep(200); // dá tempo de uma segunda requisição chegar, se houvesse

        assertEquals(1, requestCount.get());
    }

    // ==================== CT14 getTask ====================

    private static final String TASK = "b4e2d3c5-6c7f-4a81-9ba2-1d3e4f5a6b7c";
    private static final String HASH = "0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060";
    private static final String UPDATED_AT = "\"updatedAt\":\"2026-10-20T14:36:10Z\"";

    /** Corpo do CT14 com os campos dados (já em JSON) e o updatedAt. */
    private static String taskBody(String fields) {
        return "{\"taskId\":\"" + TASK + "\"," + fields + "," + UPDATED_AT + "}";
    }

    private static TaskState getTask(String baseUrl) {
        return client(baseUrl).getTask(TASK);
    }

    /** O 200 é recusado e o log diz só "fora do formato", sem o corpo; uma requisição. */
    private void assertTaskBodyRefused(String body) throws Exception {
        String base = startServer(200, body);
        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertThrows(BlockchainUnavailableException.class, () -> getTask(base), body);
            assertEquals("WARN CT14 getTask: HTTP 200 com corpo fora do formato", logs.all(), body);
        }
        assertEquals(1, requestCount.getAndSet(0), body);
        stopServer();
    }

    /** O log do CT14 nunca traz o taskId (nas duas caixas), o hash, corpo, chave nem URL. */
    private static void assertTaskLogClean(String log) {
        String lower = log.toLowerCase();
        assertFalse(lower.contains(TASK), log);
        assertFalse(lower.contains(HASH.substring(2)), log);
        assertFalse(log.contains("mensagem-secreta-do-corpo"), log);
        assertFalse(log.contains(SERVICE_KEY), log);
        assertFalse(log.contains("127.0.0.1"), log);
        assertFalse(log.contains("/tasks"), log);
    }

    // ---------- sucesso ----------

    @Test
    void getTaskIsAGetToTasksWithServiceKeyAndNoBody() throws Exception {
        String base = startServer(200, taskBody("\"status\":\"PENDING\""));

        getTask(base);

        assertEquals("GET", receivedMethod);
        assertEquals("/tasks/" + TASK, receivedRawPath);
        assertNull(receivedQuery);
        assertEquals(SERVICE_KEY, receivedServiceKey);
        assertEquals("", receivedBody);
        assertEquals(1, requestCount.get());
    }

    @Test
    void validTaskStatesAreRead() throws Exception {
        record Case(String fields, TaskState expected) {
        }
        List<Case> cases = List.of(
                new Case("\"status\":\"PENDING\"",
                        new TaskState(TransactionStatus.PENDING, null, null)),
                new Case("\"status\":\"PENDING\",\"transactionHash\":null,\"failureReason\":null",
                        new TaskState(TransactionStatus.PENDING, null, null)),
                new Case("\"status\":\"PENDING\",\"transactionHash\":\"" + HASH + "\"",
                        new TaskState(TransactionStatus.PENDING, HASH, null)),
                new Case("\"status\":\"CONFIRMED\",\"transactionHash\":\"" + HASH + "\"",
                        new TaskState(TransactionStatus.CONFIRMED, HASH, null)),
                new Case("\"status\":\"FAILED\",\"failureReason\":\"REJECTED_BY_NETWORK\"",
                        new TaskState(TransactionStatus.FAILED, null,
                                FailureReason.REJECTED_BY_NETWORK)),
                new Case("\"status\":\"FAILED\",\"failureReason\":\"INTERNAL_ERROR\"",
                        new TaskState(TransactionStatus.FAILED, null,
                                FailureReason.INTERNAL_ERROR)),
                new Case("\"status\":\"FAILED\",\"transactionHash\":\"" + HASH
                        + "\",\"failureReason\":\"TRANSACTION_REVERTED\"",
                        new TaskState(TransactionStatus.FAILED, HASH,
                                FailureReason.TRANSACTION_REVERTED)));

        for (Case c : cases) {
            String base = startServer(200, taskBody(c.fields()));
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                assertEquals(c.expected(), getTask(base), c.fields());
                assertEquals(0, logs.lines().size(), c.fields());
            }
            stopServer();
        }
    }

    @Test
    void responseTaskIdIsComparedWithoutCase() throws Exception {
        String base = startServer(200, "{\"taskId\":\"" + TASK.toUpperCase() + "\",\"status\":\"PENDING\"}");

        assertEquals(TransactionStatus.PENDING, getTask(base).status());
    }

    @Test
    void extraFieldsAndMissingUpdatedAtAreIgnored() throws Exception {
        String base = startServer(200, "{\"taskId\":\"" + TASK + "\",\"status\":\"PENDING\",\"outro\":1}");

        assertEquals(TransactionStatus.PENDING, getTask(base).status());
    }

    // ---------- 200 fora do formato ----------

    @Test
    void taskIdDifferentMissingOrNotTextIsRefused() throws Exception {
        assertTaskBodyRefused("{\"taskId\":\"a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b\",\"status\":\"PENDING\"}");
        assertTaskBodyRefused("{\"status\":\"PENDING\"}");
        assertTaskBodyRefused("{\"taskId\":null,\"status\":\"PENDING\"}");
        assertTaskBodyRefused("{\"taskId\":123,\"status\":\"PENDING\"}");
        assertTaskBodyRefused("{\"taskId\":[\"" + TASK + "\"],\"status\":\"PENDING\"}");
    }

    @Test
    void statusMissingLowercaseOrUnknownIsRefused() throws Exception {
        assertTaskBodyRefused(taskBody("\"outro\":1"));
        assertTaskBodyRefused(taskBody("\"status\":null"));
        assertTaskBodyRefused(taskBody("\"status\":\"pending\""));
        assertTaskBodyRefused(taskBody("\"status\":\"DONE\""));
        assertTaskBodyRefused(taskBody("\"status\":1"));
    }

    @Test
    void invalidHashIsRefused() throws Exception {
        for (String hash : List.of("\"0x5c504ed4\"", "\"" + HASH.substring(2) + "00\"",
                "\"" + HASH.substring(0, 65) + "g\"", "\"" + HASH + "0\"", "\"\"", "1", "true")) {
            assertTaskBodyRefused(taskBody("\"status\":\"PENDING\",\"transactionHash\":" + hash));
        }
    }

    @Test
    void unknownFailureReasonIsRefused() throws Exception {
        assertTaskBodyRefused(taskBody("\"status\":\"FAILED\",\"failureReason\":\"OUTRO\""));
        assertTaskBodyRefused(taskBody("\"status\":\"FAILED\",\"failureReason\":\"rejected_by_network\""));
        assertTaskBodyRefused(taskBody("\"status\":\"FAILED\",\"failureReason\":1"));
    }

    @Test
    void incoherentStatusReasonAndHashAreRefused() throws Exception {
        // FAILED sem motivo.
        assertTaskBodyRefused(taskBody("\"status\":\"FAILED\""));
        assertTaskBodyRefused(taskBody("\"status\":\"FAILED\",\"transactionHash\":\"" + HASH + "\""));
        // Motivo com PENDING ou CONFIRMED.
        assertTaskBodyRefused(taskBody("\"status\":\"PENDING\",\"failureReason\":\"INTERNAL_ERROR\""));
        assertTaskBodyRefused(taskBody("\"status\":\"CONFIRMED\",\"transactionHash\":\"" + HASH
                + "\",\"failureReason\":\"INTERNAL_ERROR\""));
        // CONFIRMED sem hash.
        assertTaskBodyRefused(taskBody("\"status\":\"CONFIRMED\""));
        assertTaskBodyRefused(taskBody("\"status\":\"CONFIRMED\",\"transactionHash\":null"));
    }

    @Test
    void taskBodyThatIsNotAnObjectIsRefused() throws Exception {
        assertTaskBodyRefused("");
        assertTaskBodyRefused("[]");
        assertTaskBodyRefused("null");
        assertTaskBodyRefused("\"PENDING\"");
        assertTaskBodyRefused("isto não é JSON");
        assertTaskBodyRefused("{\"taskId\":\"" + TASK + "\",");
    }

    // ---------- status de erro ----------

    @Test
    void getTaskErrorStatusesAreUnavailableWithTheRightLogLevel() throws Exception {
        record Case(int status, String body, String expectedLog) {
        }
        String secret = ",\"message\":\"mensagem-secreta-do-corpo\"}";
        List<Case> cases = List.of(
                new Case(401, "{\"code\":\"SERVICE_KEY_INVALID\"" + secret,
                        "ERROR CT14 getTask: HTTP 401 SERVICE_KEY_INVALID;"
                                + " a SERVICE_KEY difere da configurada no Blockchain Service"),
                new Case(404, "{\"code\":\"TASK_NOT_FOUND\"" + secret, "WARN CT14 getTask: HTTP 404 TASK_NOT_FOUND"),
                new Case(500, "{\"code\":\"INTERNAL_ERROR\"" + secret, "WARN CT14 getTask: HTTP 500 INTERNAL_ERROR"),
                new Case(502, "<html>Bad Gateway</html>", "WARN CT14 getTask: HTTP 502 sem code"),
                new Case(201, taskBody("\"status\":\"PENDING\""), "WARN CT14 getTask: HTTP 201 sem code"),
                new Case(204, "", "WARN CT14 getTask: HTTP 204 sem code"));

        for (Case c : cases) {
            String base = startServer(c.status(), c.body());
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                assertThrows(BlockchainUnavailableException.class, () -> getTask(base), c.body());

                assertEquals(c.expectedLog(), logs.all(), c.body());
                assertTaskLogClean(logs.all());
            }
            assertEquals(1, requestCount.getAndSet(0), c.body());
            stopServer();
        }
    }

    // ---------- rede ----------

    @Test
    void getTaskTimeoutConstantIsFiveSeconds() {
        assertEquals(Duration.ofSeconds(5), HttpBlockchainClient.GET_TASK_TIMEOUT);
    }

    @Test
    void getTaskUsesItsOwnTimeoutAndASingleRequest() throws Exception {
        String base = startServer(200, taskBody("\"status\":\"PENDING\""), 2_000);
        // Tempo geral longo (como BLOCKCHAIN_TIMEOUT_SECONDS) e o do CT14 curto.
        HttpBlockchainClient client = new HttpBlockchainClient(base, SERVICE_KEY, Duration.ofSeconds(10),
                Duration.ofMillis(300));

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertTimeoutPreemptively(Duration.ofMillis(1_500),
                    () -> assertThrows(BlockchainUnavailableException.class, () -> client.getTask(TASK)));
            assertEquals("WARN CT14 getTask: falha de rede (HttpTimeoutException)", logs.all());
            assertTaskLogClean(logs.all());
        }
        assertEquals(1, requestCount.get());
    }

    @Test
    void getTaskConnectionRefusedFails() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
            assertThrows(BlockchainUnavailableException.class, () -> getTask("http://127.0.0.1:" + closedPort));
            assertEquals("WARN CT14 getTask: falha de rede (ConnectException)", logs.all());
        }
    }

    @Test
    void getTaskServerErrorIsNotRetried() throws Exception {
        String base = startServer(500, "{\"code\":\"INTERNAL_ERROR\"}");

        assertThrows(BlockchainUnavailableException.class, () -> getTask(base));
        Thread.sleep(200); // dá tempo de uma segunda requisição chegar, se houvesse

        assertEquals(1, requestCount.get());
    }

    // ---------- taskId fora do formato: nenhuma chamada ----------

    @Test
    void taskIdOutOfFormatMakesNoCall() throws Exception {
        String base = startServer(200, taskBody("\"status\":\"PENDING\""));
        HttpBlockchainClient client = client(base);

        for (String taskId : Arrays.asList(null, "", "abc", "../../wallets/x/balance/aaaaaaaaaaaaaaa",
                TASK + "/", TASK.substring(1) + "?", TASK.substring(1) + "/", TASK.substring(1) + "\n",
                TASK.substring(1) + "g", TASK + "0")) {
            try (LogCapture logs = new LogCapture(HttpBlockchainClient.class)) {
                assertThrows(BlockchainUnavailableException.class, () -> client.getTask(taskId), taskId);
                assertEquals("ERROR CT14 getTask: taskId fora do formato; a chamada não foi feita",
                        logs.all(), taskId);
            }
        }

        assertEquals(0, requestCount.get());
    }
}
