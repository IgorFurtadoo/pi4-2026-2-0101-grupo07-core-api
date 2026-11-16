package com.lemma.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cópia local de uma transação (tabela eth_transaction, CT12). A chave é o taskId
 * criado pelo Blockchain Service no CT10. O destino fica como o usuário enviou;
 * datas em UTC. Esta entidade nunca sai pelo JSON.
 *
 * Mapeamento conferido contra o Hibernate 7.4 em validate: task_id é CHAR(36), e
 * sem o JdbcTypeCode o validate esperaria VARCHAR; os enums em texto aceitam a
 * coluna VARCHAR (o Hibernate trata ENUM e VARCHAR como equivalentes).
 */
@Entity
@Table(name = "eth_transaction")
public class EthTransaction {

    /** UUID em texto, criado pelo Blockchain Service. */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "task_id", length = 36, nullable = false)
    private String taskId;

    /** Dono da transação (app_user.id). Toda leitura filtra por ele. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "destination_address", length = 42, nullable = false)
    private String destinationAddress;

    @Column(name = "amount_eth", precision = 38, scale = 18, nullable = false)
    private BigDecimal amountEth;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private TransactionStatus status;

    /** Nulo até o Blockchain Service informar. */
    @Column(name = "transaction_hash", length = 66)
    private String transactionHash;

    /** Só preenchido quando o estado é FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 32)
    private FailureReason failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Exigido pelo Hibernate. */
    protected EthTransaction() {
    }

    /** Transação recém-aceita pelo CT10: PENDING, sem hash e sem motivo; criada e atualizada no mesmo instante. */
    public EthTransaction(String taskId, long userId, String destinationAddress, BigDecimal amountEth, Instant now) {
        this.taskId = taskId;
        this.userId = userId;
        this.destinationAddress = destinationAddress;
        this.amountEth = amountEth;
        this.status = TransactionStatus.PENDING;
        this.transactionHash = null;
        this.failureReason = null;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getTaskId() {
        return taskId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public BigDecimal getAmountEth() {
        return amountEth;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getTransactionHash() {
        return transactionHash;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
