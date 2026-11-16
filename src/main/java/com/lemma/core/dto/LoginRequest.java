package com.lemma.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Corpo do CT3 authenticateUser. Os dois campos precisam ser texto JSON (JsonConfig);
 * campo ausente fica nulo e o AuthService responde REQUEST_INVALID. Campo extra é
 * ignorado.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(String email, String password) {

    /** Nunca imprime o e-mail nem a senha (o toString padrão do record os mostraria). */
    @Override
    public String toString() {
        return "LoginRequest[email=***, password=***]";
    }
}
