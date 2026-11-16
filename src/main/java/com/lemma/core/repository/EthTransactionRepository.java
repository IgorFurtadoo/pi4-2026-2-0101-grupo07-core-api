package com.lemma.core.repository;

import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;
import org.hibernate.SessionFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * Acesso à tabela eth_transaction (CT12). Cada método abre e fecha a própria
 * transação (seção 4.2 do desenho). Não loga nada; quem chama decide o que vai
 * ao log. Qualquer falha do banco sobe como está.
 */
public class EthTransactionRepository {

    private final SessionFactory sessionFactory;

    public EthTransactionRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /** Grava a transação. Qualquer falha (banco fora, taskId repetido) sobe como está. */
    public void save(EthTransaction transaction) {
        sessionFactory.inTransaction(session -> session.persist(transaction));
    }

    /**
     * Leitura do Frontend (CT13): só a transação deste taskId que pertence a este
     * usuário. A de outro usuário fica vazia, igual à inexistente.
     */
    public Optional<EthTransaction> findByTaskIdAndUserId(String taskId, long userId) {
        return sessionFactory.fromTransaction(session -> session
                .createSelectionQuery("from EthTransaction where taskId = :taskId and userId = :userId",
                        EthTransaction.class)
                .setParameter("taskId", taskId)
                .setParameter("userId", userId)
                .uniqueResultOptional());
    }

    /** Leitura do webhook (CT15), que vem do Blockchain Service e não tem usuário. */
    public Optional<EthTransaction> findByTaskId(String taskId) {
        return sessionFactory.fromTransaction(session -> Optional.ofNullable(
                session.find(EthTransaction.class, taskId)));
    }

    /**
     * Regra de convergência (seção 8.4 do desenho): uma única atualização
     * condicional, sem leitura prévia. Só altera a linha que ainda está PENDING;
     * estado final nunca regride nem muda, e o webhook e a consulta ativa nunca
     * disputam (o banco serializa as duas atualizações).
     *
     * @return quantas linhas mudaram: 1, ou 0 se a transação não existe ou já está
     *         em estado final
     */
    public int updateIfPending(String taskId, TransactionStatus status, String transactionHash,
                               FailureReason failureReason, Instant now) {
        return sessionFactory.fromTransaction(session -> session
                .createMutationQuery("update EthTransaction"
                        + " set status = :status, transactionHash = :transactionHash,"
                        + " failureReason = :failureReason, updatedAt = :updatedAt"
                        + " where taskId = :taskId and status = :pending")
                .setParameter("status", status)
                .setParameter("transactionHash", transactionHash)
                .setParameter("failureReason", failureReason)
                .setParameter("updatedAt", now)
                .setParameter("taskId", taskId)
                .setParameter("pending", TransactionStatus.PENDING)
                .executeUpdate());
    }
}
