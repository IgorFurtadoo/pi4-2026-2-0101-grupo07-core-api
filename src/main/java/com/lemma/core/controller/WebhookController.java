package com.lemma.core.controller;

import com.lemma.core.dto.TaskCompletedRequest;
import com.lemma.core.exception.ApiException;
import com.lemma.core.service.TransactionService;
import io.javalin.http.Context;

/**
 * Webhook do Blockchain Service, protegido pelo ServiceKeyFilter (não usa token).
 * Sem regra de negócio: lê o corpo, chama o service e responde 204 sem corpo.
 * Corpo ausente, malformado ou com tipo errado vira 400 REQUEST_INVALID no
 * ErrorHandler; o corpo literal null é tratado aqui.
 */
public class WebhookController {

    private final TransactionService transactionService;

    public WebhookController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** CT15: POST /webhooks/tasks → 204 sem corpo. */
    public void notifyTaskCompleted(Context ctx) {
        // O JSON literal null faria o Javalin lançar NullPointerException (500); tratamos antes.
        if ("null".equals(ctx.body().strip())) {
            throw new ApiException(400, "REQUEST_INVALID", "Corpo da requisição inválido.");
        }
        TaskCompletedRequest request = ctx.bodyAsClass(TaskCompletedRequest.class);
        transactionService.notifyTaskCompleted(request);
        ctx.status(204);
    }
}
