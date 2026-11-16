package com.lemma.core.dto;

/** Saída 202 do CT9: a solicitação foi aceita (não confirmada); status é sempre PENDING. */
public record SendTransactionResponse(String taskId, String status) {
}
