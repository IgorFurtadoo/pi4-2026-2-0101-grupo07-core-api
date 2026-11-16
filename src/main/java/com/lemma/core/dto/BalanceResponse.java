package com.lemma.core.dto;

import java.math.BigDecimal;

/**
 * Saída do CT6 getBalance. O endereço é o do app_user; o saldo é o valor exato do
 * CT7, escrito como número JSON em notação plana (JsonConfig); a rede é SEPOLIA.
 */
public record BalanceResponse(String ethereumAddress, BigDecimal balanceEth, String network) {
}
