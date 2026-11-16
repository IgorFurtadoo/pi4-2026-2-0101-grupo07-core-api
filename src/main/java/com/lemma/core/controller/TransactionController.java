package com.lemma.core.controller;

import com.lemma.core.dto.SendTransactionRequest;
import com.lemma.core.exception.ApiException;
import com.lemma.core.security.TokenFilter;
import com.lemma.core.service.TransactionService;
import io.javalin.http.Context;

/**
 * Rotas de envio e de acompanhamento, protegidas pelo TokenFilter. Sem regra de
 * negócio: lê o id do usuário do token e o corpo ou o caminho, chama o service e
 * devolve o DTO. O Frontend não informa a origem. Corpo ausente, malformado ou com tipo errado vira 400
 * REQUEST_INVALID no ErrorHandler; o corpo literal null é tratado aqui.
 */
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** CT9: POST /transactions → 202 { taskId, status: PENDING }. */
    public void send(Context ctx) {
        // O JSON literal null faria o Javalin lançar NullPointerException (500); tratamos antes.
        if ("null".equals(ctx.body().strip())) {
            throw new ApiException(400, "REQUEST_INVALID", "Corpo da requisição inválido.");
        }
        SendTransactionRequest request = ctx.bodyAsClass(SendTransactionRequest.class);
        ctx.status(202).json(transactionService.send(TokenFilter.userId(ctx), request));
    }

    /** CT13: GET /transactions/{taskId} → 200 { taskId, status, destinationAddress, amountEth, ... }. */
    public void get(Context ctx) {
        ctx.json(transactionService.get(TokenFilter.userId(ctx), ctx.pathParam("taskId")));
    }
}
