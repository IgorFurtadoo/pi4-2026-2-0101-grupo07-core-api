package com.lemma.core.controller;

import com.lemma.core.dto.ErrorResponse;
import com.lemma.core.exception.ApiException;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler central de erros, registrado no Main.
 *
 * ApiException vira o status e o código dela; qualquer outra exceção vira
 * 500 INTERNAL_ERROR sem expor detalhe interno ao chamador. Rota inexistente (404)
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
        config.routes.exception(Exception.class, ErrorHandler::handleUnexpected);
    }

    static void handleApiException(ApiException e, Context ctx) {
        ctx.status(e.getStatus()).json(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    static void handleUnexpected(Exception e, Context ctx) {
        // Loga só método e caminho; nunca o corpo da requisição.
        log.error("Erro inesperado em {} {}", ctx.method(), ctx.path(), e);
        ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Erro interno."));
    }
}
