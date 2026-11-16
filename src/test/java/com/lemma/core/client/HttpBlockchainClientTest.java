package com.lemma.core.client;

import com.lemma.core.LogCapture;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o CT4 e o CT7 contra um Blockchain Service falso (servidor HTTP do próprio
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
}
