package com.lemma.core;

import com.lemma.core.config.AppConfig;
import com.lemma.core.service.FakeBlockchainClient;
import com.lemma.core.service.InMemoryEthTransactionRepository;
import com.lemma.core.service.InMemoryUserRepository;
import com.lemma.core.service.TransactionService;
import com.lemma.core.socket.TaskNoticeListener;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Main.startTaskNoticeListener: a porta 0 desativa o canal de socket (CT27). */
class MainTaskSocketTest {

    private final TransactionService transactionService = new TransactionService(new InMemoryUserRepository(),
            new InMemoryEthTransactionRepository(), new FakeBlockchainClient(), Clock.systemUTC());

    private static AppConfig config(int taskSocketPort) {
        return new AppConfig(8080, "jdbc:mysql://core-db:3306/core", "app", "test-db-password",
                "test-jwt-secret-0123456789abcdef", 240, "test-service-key",
                "http://blockchain-service:8081", "http://external-data-service:8082", 15, 15, 10, 60,
                "http://localhost:5173", "localhost", taskSocketPort);
    }

    private static boolean listenerThreadRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("task-notice-listener") && t.isAlive());
    }

    @Test
    void portZeroDoesNotStartListener() {
        assertNull(Main.startTaskNoticeListener(config(0), transactionService));
        assertFalse(listenerThreadRunning());
    }

    @Test
    void startsDaemonListenerWhenPortIsSet() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            TaskNoticeListener listener = Main.startTaskNoticeListener(config(server.getLocalPort()),
                    transactionService);
            try {
                assertNotNull(listener);
                assertTrue(listener.isAlive());
                assertTrue(listener.isDaemon());
                assertEquals("task-notice-listener", listener.getName());
            } finally {
                listener.shutdown();
                listener.join(2000);
            }
            assertFalse(listener.isAlive());
        }
    }
}
