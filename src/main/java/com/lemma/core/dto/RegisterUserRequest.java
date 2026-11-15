package com.lemma.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Corpo do CT1 registerUser. Os dois campos precisam ser texto JSON (JsonConfig);
 * campo ausente fica nulo e o UserService responde com o código do próprio campo.
 * Campo extra é ignorado.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterUserRequest(String email, String password) {

    /** Nunca imprime a senha (o toString padrão do record a mostraria). */
    @Override
    public String toString() {
        return "RegisterUserRequest[email=***, password=***]";
    }
}
