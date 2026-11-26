package com.lemma.core.socket;

import com.lemma.core.LogCapture;
import com.lemma.core.dto.TaskCompletedRequest;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.TransactionStatus;
import com.lemma.core.service.FakeBlockchainClient;
import com.lemma.core.service.InMemoryEthTransactionRepository;
import com.lemma.core.service.InMemoryUserRepository;
import com.lemma.core.service.TransactionService;
import com.lemma.socket.ShutdownNotice;
import com.lemma.socket.SubscribeRequest;
import com.lemma.socket.TaskCompletedNotice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskNoticeListener contra um servidor falso em loopback (ServerSocket na porta 0),
 * que se comporta como o do Blockchain Service: ObjectOutputStream antes do
 * ObjectInputStream e reset() depois de cada envio. O TransactionService é o real,
 * com repositórios em memória, e registra cada chamada do CT15.
 */
class TaskNoticeListenerTest {

    private static final String SERVICE_KEY = "chave-de-teste-do-socket";
    private static final String HASH = "0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060";
    private static final String UPDATED_AT = "2026-10-20T14:36:10Z";
    private static final Duration RETRY = Duration.ofMillis(50);

    private final InMemoryEthTransactionRepository transactions = new InMemoryEthTransactionRepository();
    private final RecordingTransactionService service = new RecordingTransactionService(transactions);
    private FakeServer server;
    private TaskNoticeListener listener;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeServer();
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (listener != null) {
            listener.shutdown();
            listener.join(2000);
            assertFalse(listener.isAlive(), "a thread deveria parar no shutdown()");
        }
        server.close();
    }

    private void startListener(int port) {
        listener = new TaskNoticeListener("localhost", port, SERVICE_KEY, service, RETRY);
        listener.start();
    }

    private static String taskId(int n) {
        return FakeBlockchainClient.taskId(n);
    }

    private void savePending(String taskId) {
        transactions.save(new EthTransaction(taskId, 1, "0x5B38Da6a701c568545dCfcB03FcB875f56beddC4",
                new BigDecimal("0.01").setScale(18), Instant.parse("2026-10-20T14:35:00Z")));
    }

    private static TaskCompletedNotice confirmed(String taskId) {
        return new TaskCompletedNotice(taskId, "CONFIRMED", HASH, null, UPDATED_AT);
    }

    /** Resultado da próxima chamada ao CT15: "OK" ou o código do erro. */
    private String nextOutcome() throws InterruptedException {
        String outcome = service.outcomes.poll(5, TimeUnit.SECONDS);
        assertNotNull(outcome, "o aviso não chegou ao TransactionService");
        return outcome;
    }

    @Test
    void subscribesWithServiceKey() throws Exception {
        startListener(server.port());

        SubscribeRequest request = server.accept();

        assertEquals(SERVICE_KEY, request.getServiceKey());
        assertFalse(request.toString().contains(SERVICE_KEY));
    }

    @Test
    void appliesNoticeWithSameArgumentsAsWebhook() throws Exception {
        savePending(taskId(1));
        startListener(server.port());
        server.accept();

        server.send(new TaskCompletedNotice(taskId(1), "FAILED", null, "REJECTED_BY_NETWORK", UPDATED_AT));

        assertEquals("OK", nextOutcome());
        assertEquals(new TaskCompletedRequest(taskId(1), "FAILED", null, "REJECTED_BY_NETWORK", UPDATED_AT),
                service.received.poll());
        EthTransaction row = transactions.row(taskId(1));
        assertEquals(TransactionStatus.FAILED, row.getStatus());
        assertNull(row.getTransactionHash());
        assertEquals("REJECTED_BY_NETWORK", row.getFailureReason().name());
    }

    @Test
    void receives300NoticesOnSameConnection() throws Exception {
        for (int i = 1; i <= 300; i++) {
            savePending(taskId(i));
        }
        startListener(server.port());
        server.accept();

        for (int i = 1; i <= 300; i++) {
            server.send(confirmed(taskId(i)));
        }

        for (int i = 1; i <= 300; i++) {
            assertEquals("OK", nextOutcome(), "aviso " + i);
            assertEquals(taskId(i), service.received.poll().taskId());
        }
        assertEquals(TransactionStatus.CONFIRMED, transactions.row(taskId(300)).getStatus());
        assertEquals(1, server.accepted, "a conexão não deveria ter caído");
    }

    @Test
    void reconnectsAfterServerClosesConnection() throws Exception {
        savePending(taskId(1));
        startListener(server.port());
        server.accept();

        server.dropConnection();
        SubscribeRequest again = server.accept();
        server.send(confirmed(taskId(1)));

        assertEquals(SERVICE_KEY, again.getServiceKey());
        assertEquals("OK", nextOutcome());
    }

    @Test
    void reconnectsAfterShutdownNotice() throws Exception {
        savePending(taskId(1));
        try (LogCapture logs = new LogCapture(TaskNoticeListener.class)) {
            startListener(server.port());
            server.accept();

            server.send(new ShutdownNotice());
            server.accept();
            server.send(confirmed(taskId(1)));

            assertEquals("OK", nextOutcome());
            assertTrue(logs.all().contains("vai desligar"), logs.all());
        }
    }

    @Test
    void ignoresUnknownTaskAndInvalidNoticeWithoutStopping() throws Exception {
        savePending(taskId(1));
        try (LogCapture logs = new LogCapture(TaskNoticeListener.class)) {
            startListener(server.port());
            server.accept();

            server.send(confirmed(taskId(99)));                                               // desconhecida
            server.send(new TaskCompletedNotice(taskId(1), "PENDING", null, null, UPDATED_AT));  // status inválido
            server.send(new TaskCompletedNotice(taskId(1), "CONFIRMED", null, null, UPDATED_AT)); // sem hash
            server.send(new TaskCompletedNotice(taskId(1), "FAILED", HASH, null, UPDATED_AT));    // sem motivo
            server.send(confirmed(taskId(1)));

            assertEquals("TRANSACTION_NOT_FOUND", nextOutcome());
            assertEquals("REQUEST_INVALID", nextOutcome());
            assertEquals("REQUEST_INVALID", nextOutcome());
            assertEquals("REQUEST_INVALID", nextOutcome());
            assertEquals("OK", nextOutcome());
            assertEquals(TransactionStatus.CONFIRMED, transactions.row(taskId(1)).getStatus());
            assertEquals(1, server.accepted, "a conexão não deveria ter caído");
            assertTrue(listener.isAlive());
            assertTrue(logs.lines().contains("WARN socket: aviso ignorado (taskId=" + taskId(99)
                    + ", status=CONFIRMED, TRANSACTION_NOT_FOUND)"), logs.all());
        }
    }

    @Test
    void rejectsObjectOfDisallowedClassAndClosesConnection() throws Exception {
        startListener(server.port());
        server.accept();

        server.send(new Date());

        // O cliente fecha a conexão: a leitura do lado do servidor termina.
        IOException closed = assertThrows(IOException.class, server::readFromClient);
        assertFalse(closed instanceof SocketTimeoutException, "o cliente deveria ter fechado a conexão");
        // E volta a assinar numa conexão nova, sem ter chamado o CT15.
        server.accept();
        assertTrue(service.received.isEmpty());
    }

    @Test
    void warnsOnlyOncePerOutageAndNeverLogsKeyOrAddress() throws Exception {
        int closedPort = server.port();
        server.close(); // nada escutando nessa porta

        try (LogCapture logs = new LogCapture(TaskNoticeListener.class)) {
            startListener(closedPort);
            Thread.sleep(RETRY.toMillis() * 8);
            listener.shutdown();
            listener.join(2000);

            long warnings = logs.lines().stream().filter(l -> l.contains("socket indisponível")).count();
            assertEquals(1, warnings, logs.all());
            assertFalse(logs.all().contains(SERVICE_KEY));
            assertFalse(logs.all().contains("localhost"));
            assertFalse(logs.all().contains(String.valueOf(closedPort)));
        }
    }

    /** CT15 real, registrando o pedido e o resultado (filas: passam os dados entre as threads). */
    private static final class RecordingTransactionService extends TransactionService {
        final BlockingQueue<TaskCompletedRequest> received = new LinkedBlockingQueue<>();
        final BlockingQueue<String> outcomes = new LinkedBlockingQueue<>();

        RecordingTransactionService(InMemoryEthTransactionRepository transactions) {
            super(new InMemoryUserRepository(), transactions, new FakeBlockchainClient(), Clock.systemUTC());
        }

        @Override
        public void notifyTaskCompleted(TaskCompletedRequest request) {
            received.add(request);
            try {
                super.notifyTaskCompleted(request);
                outcomes.add("OK");
            } catch (ApiException e) {
                outcomes.add(e.getCode());
                throw e;
            }
        }
    }

    /** Servidor falso de uma conexão por vez, no mesmo formato do Blockchain Service. */
    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private Socket connection;
        private ObjectOutputStream out;
        private ObjectInputStream in;
        int accepted;

        FakeServer() throws IOException {
            serverSocket.setSoTimeout(5000);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        /** Aceita a próxima conexão e devolve a assinatura que o cliente mandou. */
        SubscribeRequest accept() throws Exception {
            connection = serverSocket.accept();
            connection.setSoTimeout(5000);
            accepted++;
            out = new ObjectOutputStream(connection.getOutputStream());
            out.flush();
            in = new ObjectInputStream(connection.getInputStream());
            return (SubscribeRequest) in.readObject();
        }

        void send(Object message) throws IOException {
            out.writeObject(message);
            out.flush();
            out.reset();
        }

        Object readFromClient() throws Exception {
            return in.readObject();
        }

        void dropConnection() throws IOException {
            connection.close();
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
                if (connection != null) {
                    connection.close();
                }
            } catch (IOException e) {
                // nada a fazer
            }
        }
    }
}
