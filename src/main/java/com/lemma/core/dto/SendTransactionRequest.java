package com.lemma.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Corpo do CT9 sendTransaction. destinationAddress precisa ser texto JSON
 * (JsonConfig); amountEth aceita número JSON ou texto numérico ("0.01"), lido
 * como BigDecimal exato. Campo ausente fica nulo e o TransactionService responde
 * com o código do próprio campo. Campo extra (sourceAddress, userId...) é
 * ignorado: a origem é sempre a carteira do usuário do token.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendTransactionRequest(String destinationAddress, BigDecimal amountEth) {
}
