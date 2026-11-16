package com.lemma.core.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * Emite e valida o token JWT do login (CT3, seções 8.2 e 9 do desenho, ADR 0004).
 *
 * Token HS256 com só três claims: sub (id do usuário, em texto), iat e exp. Na
 * validação o algoritmo é fixo em HS256: um token com "alg": "none" ou assinado
 * com outro algoritmo é recusado. A assinatura e o exp são conferidos sem
 * tolerância de relógio.
 *
 * Não loga nada: nem o token, nem a mensagem das exceções da biblioteca (ela pode
 * repetir o conteúdo do token).
 */
public class JwtService {

    /**
     * Inteiro decimal positivo, sem sinal nem zero à esquerda, com até 19 dígitos.
     * O que passa disso e não cabe em long é recusado no parse.
     */
    private static final Pattern USER_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final Duration ttl;
    private final Clock clock;

    /**
     * @param secret     segredo do HS256 (JWT_SECRET); os bytes UTF-8 são a chave do HMAC
     * @param ttlMinutes validade do token (JWT_TTL_MINUTES)
     * @param clock      relógio; os testes usam um fixo para avançar o tempo sem dormir
     */
    public JwtService(String secret, int ttlMinutes, Clock clock) {
        this.algorithm = Algorithm.HMAC256(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.clock = clock;
        // O verificador recusa token cujo cabeçalho traga outro algoritmo. A tolerância
        // de relógio padrão da biblioteca é zero. O build(Clock) só existe na classe
        // BaseVerification; o cast é a forma documentada de injetar o relógio.
        this.verifier = ((JWTVerifier.BaseVerification) JWT.require(algorithm)).build(clock);
    }

    /**
     * Emite o token do usuário. O instante é truncado em segundos antes do cálculo,
     * para o exp do token e o expiresAt da resposta serem o mesmo valor.
     */
    public IssuedToken issue(long userId) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(ttl);
        String token = JWT.create()
                .withSubject(Long.toString(userId))
                .withIssuedAt(issuedAt)
                .withExpiresAt(expiresAt)
                .sign(algorithm);
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Valida o token e devolve o id do usuário (claim sub).
     *
     * @throws InvalidTokenException se o token for nulo, vazio, malformado, tiver
     *         assinatura ou algoritmo errado, estiver vencido, ou se faltar exp ou sub
     */
    public long verify(String token) {
        if (token == null || token.isEmpty()) {
            throw new InvalidTokenException();
        }

        DecodedJWT jwt;
        try {
            jwt = verifier.verify(token);
        } catch (RuntimeException e) {
            // Qualquer falha ao ler ou conferir o token o torna inválido. A mensagem
            // da biblioteca não é repassada: pode trazer o conteúdo do token.
            throw new InvalidTokenException();
        }

        // A biblioteca só confere o exp quando ele existe; aqui ele é obrigatório.
        if (jwt.getExpiresAtAsInstant() == null) {
            throw new InvalidTokenException();
        }
        return parseUserId(jwt.getClaim("sub").asString());
    }

    /** sub em texto JSON (asString devolve null para número ou ausência). */
    private static long parseUserId(String sub) {
        if (sub == null || !USER_ID.matcher(sub).matches()) {
            throw new InvalidTokenException();
        }
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            // 19 dígitos acima do maior long.
            throw new InvalidTokenException();
        }
    }

    /** Token emitido e o instante em que vence (o exp do token). */
    public record IssuedToken(String token, Instant expiresAt) {

        /** Nunca imprime o token (o toString padrão do record o mostraria). */
        @Override
        public String toString() {
            return "IssuedToken[token=***, expiresAt=" + expiresAt + "]";
        }
    }
}
