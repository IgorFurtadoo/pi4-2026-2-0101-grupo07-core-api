package com.lemma.core.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Uma transação do histórico de um endereço (CT24 fetchAddressTransactions), já
 * conferida pelo {@link AddressHistoryClient}.
 *
 * - direction: SENT, RECEIVED ou SELF, do ponto de vista do endereço pedido.
 * - counterpartyAddress: a outra ponta; null só na criação de contrato que falhou.
 * - amountEth: o valor exato recebido em texto, sem passar por double.
 * - status: CONFIRMED ou FAILED (transação minerada que reverteu).
 */
public record AddressTransaction(String hash, String direction, String counterpartyAddress,
                                 BigDecimal amountEth, Instant timestamp, String status) {
}
