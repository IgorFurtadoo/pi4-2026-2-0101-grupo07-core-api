package com.lemma.core.exception;

/**
 * 429 RATE_LIMIT_EXCEEDED do CT17, com o Retry-After em segundos. O ErrorHandler
 * grava o cabeçalho Retry-After; o corpo é o mesmo { "code", "message" } dos
 * outros erros.
 */
public class RateLimitExceededException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(429, "RATE_LIMIT_EXCEEDED", "Muitas requisições. Tente novamente em instantes.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
