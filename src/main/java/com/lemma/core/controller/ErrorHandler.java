package com.lemma.core.controller;

import com.fasterxml.jackson.core.JacksonException;
import com.lemma.core.dto.ErrorResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.exception.RateLimitExceededException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler central de erros, registrado no Main.
 *
 * ApiException vira o status e o código dela (o 429 do limite leva também o
 * Retry-After); erro do Jackson ao ler o corpo vira
 * 400 REQUEST_INVALID; qualquer outra exceção vira 500 INTERNAL_ERROR sem expor
 * detalhe interno ao chamador. Rota inexistente (404)
 * e método errado (405) ficam com a resposta padrão do Javalin, que tem handler
 * próprio para esses casos.
 */
public final class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private ErrorHandler() {
    }

    /** Registra os handlers na configuração do Javalin (usado pelo Main e pelo teste). */
    public static void register(JavalinConfig config) {
        config.routes.exception(ApiException.class, ErrorHandler::handleApiException);
        config.routes.exception(JacksonException.class, ErrorHandler::handleInvalidBody);
        config.routes.exception(Exception.class, ErrorHandler::handleUnexpected);
    }

    /** O 429 do limite leva também o Retry-After (ficha CT17). Nenhum dos dois vai ao log. */
    static void handleApiException(ApiException e, Context ctx) {
        if (e instanceof RateLimitExceededException rateLimit) {
            ctx.header("Retry-After", String.valueOf(rateLimit.getRetryAfterSeconds()));
        }
        ctx.status(e.getStatus()).json(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    /** Corpo ausente, JSON malformado ou campo com tipo errado (o Javalin repassa o erro do Jackson). */
    static void handleInvalidBody(JacksonException e, Context ctx) {
        // Não loga a mensagem do Jackson: ela pode trazer trechos do corpo.
        ctx.status(400).json(new ErrorResponse("REQUEST_INVALID", "Corpo da requisição inválido."));
    }

    static void handleUnexpected(Exception e, Context ctx) {
        // Loga só método e caminho; nunca o corpo da requisição.
        log.error("Erro inesperado em {} {}", ctx.method(), ctx.path(), e);
        ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Erro interno."));
    }
}
