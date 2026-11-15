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
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o CT4 contra um Blockchain Service falso (servidor HTTP do próprio JDK),
 * sem rede externa. O falso guarda a última requisição recebida.
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
}
