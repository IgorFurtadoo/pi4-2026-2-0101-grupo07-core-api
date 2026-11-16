package com.lemma.core.client;

import java.math.BigDecimal;

/**
 * Resultado do CT7 getWalletBalance, já conferido pelo cliente: saldo em ETH
 * exato (o mesmo valor e a mesma escala que o Blockchain Service mandou, entre
 * 0 e 18 casas, nunca negativo) e a rede, sempre SEPOLIA.
 */
public record BalanceResult(BigDecimal balanceEth, String network) {
}
