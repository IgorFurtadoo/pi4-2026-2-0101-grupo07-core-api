package com.lemma.core.controller;

import com.lemma.core.security.TokenFilter;
import com.lemma.core.service.WalletService;
import io.javalin.http.Context;

/**
 * Rota de saldo, protegida pelo TokenFilter. Sem regra de negócio: lê o id do
 * usuário do token, chama o service e devolve o DTO. O Frontend não informa endereço.
 */
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /** CT6: GET /wallet/balance → 200 { ethereumAddress, balanceEth, network }. */
    public void balance(Context ctx) {
        ctx.json(walletService.getBalance(TokenFilter.userId(ctx)));
    }
}
