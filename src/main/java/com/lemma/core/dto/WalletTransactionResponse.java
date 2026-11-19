package com.lemma.core.dto;

import java.math.BigDecimal;

/**
 * Um item da saída do CT26 getWalletTransactions, repassado do CT24.
 * counterpartyAddress sai como null (não ausente) na criação de contrato que
 * falhou; amountEth é o valor exato do CT24, escrito como número JSON em notação
 * plana (JsonConfig); timestamp em ISO 8601 UTC (ex.: 2026-11-16T04:12:00Z).
 */
public record WalletTransactionResponse(String hash, String direction, String counterpartyAddress,
                                        BigDecimal amountEth, String timestamp, String status) {
}
