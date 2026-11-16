package com.lemma.core.security;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;

/**
 * CORS para o Frontend (seção 6 do desenho). CORS é controle de origem do
 * navegador, não autenticação: quem não é navegador ignora estes cabeçalhos, e a
 * proteção das rotas continua sendo o TokenFilter.
 *
 * Registrado no Main com before, que o Javalin roda antes do beforeMatched do
 * TokenFilter. Assim:
 * - a pré-requisição OPTIONS (que o navegador manda sem Authorization) é
 *   respondida aqui, com 204, e não chega ao TokenFilter nem ao 405;
 * - toda resposta para a origem permitida leva Access-Control-Allow-Origin,
 *   inclusive os erros (401 TOKEN_INVALID, 404), porque o cabeçalho é gravado antes
 *   e o handler de erros não o apaga. Sem ele, o navegador esconderia o erro do
 *   Frontend.
 * - toda resposta para a origem permitida leva também
 *   Access-Control-Expose-Headers: Retry-After. Sem ele, o navegador não deixa o
 *   JavaScript do Frontend ler o Retry-After do 429 RATE_LIMIT_EXCEEDED (CT17),
 *   porque não é um cabeçalho que o navegador já libera. Na pré-requisição ele é
 *   inofensivo (o navegador o ignora), e assim a regra fica uma só.
 *
 * Só a origem configurada (CORS_ALLOWED_ORIGIN), comparada como texto exato. Outra
 * origem não recebe nenhum cabeçalho Access-Control-*: a requisição segue
 * como se não houvesse CORS, e o navegador bloqueia a leitura da resposta. Sem
 * credenciais (cookies) e sem Max-Age.
 *
 * Usamos esta classe em vez do CorsPlugin do Javalin porque o plugin devolve os
 * métodos e cabeçalhos que o navegador pedir (em vez de uma lista fixa) e, para
 * origem não permitida, marca 400 e deixa a requisição seguir até o handler.
 */
public class CorsFilter {

    static final String ALLOWED_METHODS = "GET, POST, OPTIONS";
    static final String ALLOWED_HEADERS = "Authorization, Content-Type";
    static final String EXPOSED_HEADERS = "Retry-After";

    private final String allowedOrigin;

    /** @param allowedOrigin origem do Frontend, como esquema://host[:porta] (CORS_ALLOWED_ORIGIN) */
    public CorsFilter(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    public void handle(Context ctx) {
        // A resposta muda conforme a origem: um cache no caminho não pode
        // reaproveitá-la para outra. addHeader para não apagar um Vary que já exista.
        ctx.addHeader("Vary", "Origin");

        String origin = ctx.header("Origin");
        if (!allowedOrigin.equals(origin)) {
            return;
        }
        ctx.header("Access-Control-Allow-Origin", origin);
        ctx.header("Access-Control-Expose-Headers", EXPOSED_HEADERS);

        boolean preflight = HandlerType.OPTIONS.equals(ctx.method())
                && ctx.header("Access-Control-Request-Method") != null;
        if (preflight) {
            ctx.header("Access-Control-Allow-Methods", ALLOWED_METHODS);
            ctx.header("Access-Control-Allow-Headers", ALLOWED_HEADERS);
            ctx.status(204);
            // Nada mais roda: nem o TokenFilter, nem a busca da rota (que daria 405).
            ctx.skipRemainingHandlers();
        }
    }
}
