package com.lemma.core.dto;

/**
 * Saída do CT3 authenticateUser. expiresAt é o exp do token em ISO 8601 UTC, sem
 * fração de segundo (ex.: 2026-10-20T18:30:00Z).
 */
public record LoginResponse(String token, String expiresAt) {

    /** Nunca imprime o token (o toString padrão do record o mostraria). */
    @Override
    public String toString() {
        return "LoginResponse[token=***, expiresAt=" + expiresAt + "]";
    }
}
