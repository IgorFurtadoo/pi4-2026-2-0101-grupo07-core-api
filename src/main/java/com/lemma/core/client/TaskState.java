package com.lemma.core.client;

import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;

/**
 * Resultado do CT14 getTask, já conferido pelo cliente: status; hash ausente
 * (null) ou 0x + 64 hexadecimais; motivo só com FAILED. CONFIRMED sempre tem hash.
 * O updatedAt do Blockchain Service não é lido: o Core API usa o próprio relógio.
 */
public record TaskState(TransactionStatus status, String transactionHash, FailureReason failureReason) {
}
