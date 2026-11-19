package com.lemma.core.dto;

import java.util.List;

/**
 * Saída do CT26 getWalletTransactions. O endereço é o do app_user (o mesmo
 * enviado ao CT24); a rede é SEPOLIA; transactions tem até 20 itens, mais
 * recentes primeiro, e pode ser vazia.
 */
public record WalletTransactionsResponse(String ethereumAddress, String network,
                                         List<WalletTransactionResponse> transactions) {
}
