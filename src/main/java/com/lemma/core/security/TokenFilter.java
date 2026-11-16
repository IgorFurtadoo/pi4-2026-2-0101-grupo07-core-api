package com.lemma.core.security;

import com.lemma.core.exception.ApiException;
import io.javalin.http.Context;
import io.javalin.router.Endpoint;

import java.util.Set;

/**
 * Exige o token JWT nas rotas do Frontend (premissa P1, seção 6 do desenho).
 *
 * Proteção por padrão: toda rota exige "Authorization: Bearer <token>", menos as
 * da lista de rotas públicas (Main.PUBLIC_ROUTES). Uma rota nova esquecida fica
 * protegida, nunca aberta.
 *
 * Registrado no Main com beforeMatched: o Javalin só o executa quando existe rota
 * para o caminho e o método. Rota inexistente continua 404 e método errado
 * continua 405. A pré-requisição OPTIONS do CORS é respondida antes, pelo CorsFilter.
 *
 * Token ausente, com outro esquema, inválido ou vencido: 401 TOKEN_INVALID. O
 * token nunca vai ao log. Com token válido, o id do usuário fica no contexto da
 * requisição e o controller o lê com {@link #userId(Context)}.
 *
 * O filtro não consulta o banco. O service que lê o usuário do token responde
 * 401 TOKEN_INVALID se ele não existir mais, com a mesma exceção deste filtro
 * ({@link ApiException#tokenInvalid()}).
 */
public class TokenFilter {

    private static final String USER_ID_ATTRIBUTE = "userId";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final Set<String> publicRoutes;

    /**
     * @param publicRoutes rotas sem token, no formato "MÉTODO /caminho-registrado"
     *                     (ex.: "POST /users")
     */
    public TokenFilter(JwtService jwtService, Set<String> publicRoutes) {
        this.jwtService = jwtService;
        this.publicRoutes = Set.copyOf(publicRoutes);
    }

    public void handle(Context ctx) {
        if (isPublic(ctx)) {
            return;
        }
        String token = bearerToken(ctx.header("Authorization"));
        try {
            ctx.attribute(USER_ID_ATTRIBUTE, jwtService.verify(token));
        } catch (InvalidTokenException e) {
            throw ApiException.tokenInvalid();
        }
    }

    /** Id do usuário do token, guardado pelo filtro. Só existe em rota protegida. */
    public static long userId(Context ctx) {
        Long userId = ctx.attribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            // Erro de programação: rota pública, ou filtro fora da configuração.
            throw new IllegalStateException("userId ausente: a rota não passou pelo TokenFilter");
        }
        return userId;
    }

    /**
     * Compara o padrão da rota casada (método e caminho como foram registrados),
     * não o caminho da requisição: barra final e parâmetros de caminho não mudam
     * a chave. HEAD numa rota GET não tem rota casada própria e exige token.
     */
    private boolean isPublic(Context ctx) {
        Endpoint matched = ctx.endpoints().matchedHttpEndpoint();
        return matched != null && publicRoutes.contains(matched.method.name() + " " + matched.path);
    }

    /** O token depois de "Bearer " (esquema sem diferença de maiúsculas, RFC 7235), ou null. */
    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).strip();
    }
}
