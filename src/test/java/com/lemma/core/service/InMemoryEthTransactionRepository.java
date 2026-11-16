package com.lemma.core.service;

import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;
import com.lemma.core.repository.EthTransactionRepository;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * EthTransactionRepository em memória para os testes, sem banco.
 *
 * As leituras devolvem cópias, como a entidade desanexada que o Hibernate
 * devolve: mudar a linha depois da leitura não muda o que já foi lido. O
 * updateIfPending segue a mesma condição do UPDATE real (só altera PENDING).
 *
 * NÃO prova: o Hibernate validate de eth_transaction (CHAR(36), enums em VARCHAR,
 * DECIMAL(38,18), DATETIME(3)), a chave estrangeira para app_user, a unicidade
 * real do task_id, a comparação sem diferença de caixa do MySQL nem o UPDATE
 * condicional em HQL sob concorrência. Isso é provado no banco real.
 */
public class InMemoryEthTransactionRepository extends EthTransactionRepository {

    public final List<EthTransaction> saved = new ArrayList<>();

    /** Quando não nulo, o save lança esta exceção (banco fora, chave repetida). */
    public RuntimeException failOnSave;
    /** Quando não nulo, as leituras lançam esta exceção (banco fora). */
    public RuntimeException failOnFind;
    /** Quando não nulo, o updateIfPending lança esta exceção (banco fora). */
    public RuntimeException failOnUpdate;

    public int saveCalls;
    public int findCalls;
    public int updateCalls;

    public InMemoryEthTransactionRepository() {
        super(null);
    }

    @Override
    public void save(EthTransaction transaction) {
        saveCalls++;
        if (failOnSave != null) {
            throw failOnSave;
        }
        saved.add(transaction);
    }

    @Override
    public Optional<EthTransaction> findByTaskIdAndUserId(String taskId, long userId) {
        return find(taskId).filter(row -> row.getUserId() == userId).map(InMemoryEthTransactionRepository::copy);
    }

    @Override
    public Optional<EthTransaction> findByTaskId(String taskId) {
        return find(taskId).map(InMemoryEthTransactionRepository::copy);
    }

    @Override
    public int updateIfPending(String taskId, TransactionStatus status, String transactionHash,
                               FailureReason failureReason, Instant now) {
        updateCalls++;
        if (failOnUpdate != null) {
            throw failOnUpdate;
        }
        Optional<EthTransaction> row = saved.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst();
        if (row.isEmpty() || row.get().getStatus() != TransactionStatus.PENDING) {
            return 0;
        }
        set(row.get(), status, transactionHash, failureReason, now);
        return 1;
    }

    /** A linha gravada (não uma cópia), para o teste conferir e preparar o estado. */
    public EthTransaction row(String taskId) {
        return saved.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst().orElseThrow();
    }

    /** Muda a linha gravada sem condição: prepara o estado do teste (ex.: já CONFIRMED). */
    public void setState(String taskId, TransactionStatus status, String transactionHash,
                         FailureReason failureReason, Instant now) {
        set(row(taskId), status, transactionHash, failureReason, now);
    }

    private Optional<EthTransaction> find(String taskId) {
        findCalls++;
        if (failOnFind != null) {
            throw failOnFind;
        }
        // Com diferença de caixa, como o equals do Java (o MySQL não teria).
        return saved.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst();
    }

    private static EthTransaction copy(EthTransaction row) {
        EthTransaction copy = new EthTransaction(row.getTaskId(), row.getUserId(), row.getDestinationAddress(),
                row.getAmountEth(), row.getCreatedAt());
        set(copy, row.getStatus(), row.getTransactionHash(), row.getFailureReason(), row.getUpdatedAt());
        return copy;
    }

    /** A entidade não tem setters; aqui, só no teste, por reflexão. */
    private static void set(EthTransaction row, TransactionStatus status, String transactionHash,
                            FailureReason failureReason, Instant updatedAt) {
        setField(row, "status", status);
        setField(row, "transactionHash", transactionHash);
        setField(row, "failureReason", failureReason);
        setField(row, "updatedAt", updatedAt);
    }

    private static void setField(EthTransaction row, String name, Object value) {
        try {
            Field field = EthTransaction.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(row, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
