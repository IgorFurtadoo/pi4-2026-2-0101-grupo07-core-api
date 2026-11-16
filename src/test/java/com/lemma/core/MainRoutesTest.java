package com.lemma.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Só o cadastro e o login são públicos (premissa P1). Toda rota nova, como
 * GET /wallet/balance (CT6) e POST /transactions (CT9), fica protegida pelo
 * TokenFilter sem entrar aqui.
 */
class MainRoutesTest {

    @Test
    void onlyRegisterAndLoginArePublic() {
        assertEquals(Set.of("POST /users", "POST /auth/login"), Main.PUBLIC_ROUTES);
    }
}
