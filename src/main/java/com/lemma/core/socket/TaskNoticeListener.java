package com.lemma.core.socket;

import com.lemma.core.dto.TaskCompletedRequest;
import com.lemma.core.exception.ApiException;
import com.lemma.core.service.TransactionService;
import com.lemma.socket.Message;
import com.lemma.socket.ShutdownNotice;
import com.lemma.socket.SubscribeRequest;
import com.lemma.socket.TaskCompletedNotice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Thread que ouve o canal de socket do Blockchain Service (CT27): conecta, assina com
 * a SERVICE_KEY e aplica cada TaskCompletedNotice com a mesma regra do webhook CT15.
 * Corresponde à TratadoraDeComunicadoDeDesligamento do exemplo do professor, que fica
 * ouvindo o servidor; aqui, em vez de encerrar no desligamento, reconecta.
 *
 * Nenhum log traz a chave, o endereço do servidor ou o conteúdo do aviso: só taskId e status.
 */
public class TaskNoticeListener extends Thread {

    private static final Logger log = LoggerFactory.getLogger(TaskNoticeListener.class);

    /** Espera entre uma queda e a próxima tentativa de conexão. */
    public static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final String host;
    private final int port;
    private final String serviceKey;
    private final TransactionService transactionService;
    private final Duration retryDelay;

    private volatile boolean running = true;
    private volatile Socket socket;

    public TaskNoticeListener(String host, int port, String serviceKey, TransactionService transactionService,
                              Duration retryDelay) {
        super("task-notice-listener");
        setDaemon(true);
        this.host = host;
        this.port = port;
        this.serviceKey = serviceKey;
        this.transactionService = transactionService;
        this.retryDelay = retryDelay;
    }

    @Override
    public void run() {
        boolean warned = false; // o WARN de indisponível sai uma vez por queda
        while (running) {
            boolean connected = false;
            try {
                Socket connection = new Socket();
                socket = connection;
                if (!running) {
                    break; // shutdown() chegou antes do socket novo; o finally fecha
                }
                connection.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                Partner server = new Partner(connection);
                server.send(new SubscribeRequest(serviceKey));
                connected = true;
                warned = false;
                log.info("socket: conectado ao Blockchain Service; assinatura enviada");
                listen(server);
                log.info("socket: o Blockchain Service avisou que vai desligar; desconectado");
            } catch (IOException | ClassNotFoundException | RuntimeException e) {
                if (!running) {
                    break;
                }
                if (connected) {
                    log.info("socket: desconectado ({})", e.getClass().getSimpleName());
                }
                if (!warned) {
                    log.warn("socket indisponível; tentando de novo a cada {} s", retryDelay.toSeconds());
                    warned = true;
                }
            } finally {
                closeSocket();
            }
            pause();
        }
    }

    /** Lê mensagens até chegar o ShutdownNotice; queda de conexão sai por exceção. */
    private void listen(Partner server) throws IOException, ClassNotFoundException {
        while (running) {
            Message message = server.read();
            if (message instanceof TaskCompletedNotice notice) {
                apply(notice);
            } else if (message instanceof ShutdownNotice) {
                return;
            } else {
                log.warn("socket: mensagem inesperada ignorada ({})",
                        message == null ? "null" : message.getClass().getSimpleName());
            }
        }
    }

    /**
     * Mesma chamada do WebhookController (CT15): a validação e a regra ficam no
     * TransactionService. O que o webhook responderia com erro aqui é só ignorado.
     */
    private void apply(TaskCompletedNotice notice) {
        try {
            transactionService.notifyTaskCompleted(new TaskCompletedRequest(notice.getTaskId(),
                    notice.getStatus(), notice.getTransactionHash(), notice.getFailureReason(),
                    notice.getUpdatedAt()));
            log.info("socket: aviso aplicado (taskId={}, status={})", notice.getTaskId(), notice.getStatus());
        } catch (ApiException e) {
            log.warn("socket: aviso ignorado (taskId={}, status={}, {})",
                    notice.getTaskId(), notice.getStatus(), e.getCode());
        } catch (RuntimeException e) {
            log.warn("socket: aviso ignorado (taskId={}, status={}, {})",
                    notice.getTaskId(), notice.getStatus(), e.getClass().getSimpleName());
        }
    }

    private void pause() {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            running = false;
        }
    }

    /** Para a thread: interrompe a espera e fecha o socket, o que solta a leitura bloqueada. */
    public void shutdown() {
        running = false;
        interrupt();
        closeSocket();
    }

    private void closeSocket() {
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                // nada a fazer
            }
        }
    }
}
