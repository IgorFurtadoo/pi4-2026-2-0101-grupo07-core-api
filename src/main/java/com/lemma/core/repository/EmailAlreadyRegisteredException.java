package com.lemma.core.repository;

/**
 * A gravação violou a unicidade do e-mail (uk_app_user_email): outro cadastro com
 * o mesmo e-mail chegou primeiro. Sem mensagem com dado: a mensagem original do
 * MySQL traz o e-mail e nunca é repassada.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("e-mail já cadastrado");
    }
}
