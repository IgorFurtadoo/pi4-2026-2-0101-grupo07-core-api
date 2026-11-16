package com.lemma.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Corpo do CT15 notifyTaskCompleted (webhook do Blockchain Service). Todos os
 * campos são lidos como texto JSON (JsonConfig recusa número ou booleano no
 * lugar de texto) e validados no TransactionService. Campo extra é ignorado.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskCompletedRequest(String taskId, String status, String transactionHash, String failureReason,
                                   String updatedAt) {
}
