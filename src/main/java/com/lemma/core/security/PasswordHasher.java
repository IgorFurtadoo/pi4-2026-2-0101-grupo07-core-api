package com.lemma.core.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategy;

import java.nio.charset.StandardCharsets;

/**
 * Hash de senha com bcrypt (seções 6 e 9 do desenho).
 *
 * O bcrypt só usa os primeiros 72 bytes da senha. Em vez de truncar em silêncio,
 * senha acima de 72 bytes em UTF-8 é recusada: o UserService responde
 * PASSWORD_INVALID antes de chegar aqui, e este método lança se mesmo assim
 * receber uma. A senha nunca aparece em mensagem de exceção nem em log.
 */
public class PasswordHasher {

    /** Custo de produção: 2^12 rodadas, cerca de 250 ms por hash. */
    public static final int DEFAULT_COST = 12;

    /** Limite do bcrypt, em bytes UTF-8. */
    public static final int MAX_PASSWORD_BYTES = 72;

    private static final BCrypt.Version VERSION = BCrypt.Version.VERSION_2A;

    /** Lança em vez de truncar a senha longa (segunda proteção, além da checagem abaixo). */
    private static final LongPasswordStrategy STRICT = LongPasswordStrategies.strict(VERSION);

    private final int cost;

    /** O custo é injetável para os testes usarem um baixo (mínimo do bcrypt: 4). */
    public PasswordHasher(int cost) {
        this.cost = cost;
    }

    /** Devolve o hash no formato $2a$12$... (60 caracteres). */
    public String hash(String password) {
        if (password == null || isTooLong(password)) {
            throw new IllegalArgumentException("senha ausente ou acima de " + MAX_PASSWORD_BYTES + " bytes");
        }
        return BCrypt.with(VERSION, STRICT).hashToString(cost, password.toCharArray());
    }

    /** Confere a senha contra o hash. Senha ausente ou acima do limite nunca confere. */
    public boolean verify(String password, String hash) {
        if (password == null || hash == null || isTooLong(password)) {
            return false;
        }
        return BCrypt.verifyer(VERSION, STRICT).verify(password.toCharArray(), hash).verified;
    }

    private static boolean isTooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
