package com.lemma.core.security;

/**
 * Token recusado pelo JwtService: ausente, malformado, com assinatura ou algoritmo
 * errado, vencido ou com claim inválida. Não traz mensagem nem causa de propósito:
 * a mensagem da biblioteca pode repetir o conteúdo do token, e quem chama só
 * precisa saber que o token não vale (o TokenFilter responde 401 TOKEN_INVALID).
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super();
    }
}
