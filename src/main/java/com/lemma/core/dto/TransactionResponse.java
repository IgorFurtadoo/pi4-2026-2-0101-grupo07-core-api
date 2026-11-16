package com.lemma.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Saída 200 do CT13 getTransaction. transactionHash e failureReason são
 * opcionais: quando nulos, saem ausentes do JSON (nunca como null). amountEth
 * sem zeros à direita e em notação plana (JsonConfig); updatedAt em ISO 8601 UTC,
 * em segundos (ex.: 2026-10-20T14:36:10Z).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(String taskId, String status, String destinationAddress, BigDecimal amountEth,
                                  String transactionHash, String failureReason, String updatedAt) {
}
