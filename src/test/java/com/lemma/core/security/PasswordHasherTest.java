package com.lemma.core.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custo 4 (o mínimo do bcrypt) para o teste ser rápido; produção usa 12. */
class PasswordHasherTest {

    private static final int TEST_COST = 4;

    private final PasswordHasher hasher = new PasswordHasher(TEST_COST);

    /** "€" ocupa 3 bytes em UTF-8: 24 deles somam 72 bytes em 24 caracteres. */
    private static final String EURO_72_BYTES = "€".repeat(24);

    @Test
    void hashMatchesTheSamePassword() {
        String hash = hasher.hash("Senha@12345");

        assertTrue(hasher.verify("Senha@12345", hash));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String hash = hasher.hash("Senha@12345");

        assertFalse(hasher.verify("Senha@12346", hash));
    }

    @Test
    void hashIsBcrypt2aWithTheConfiguredCostAndRandomSalt() {
        String first = hasher.hash("Senha@12345");
        String second = hasher.hash("Senha@12345");

        assertTrue(first.startsWith("$2a$04$"), first.substring(0, 7));
        assertEquals(60, first.length());
        assertNotEquals(first, second);
    }

    @Test
    void defaultCostIsTwelve() {
        assertEquals(12, PasswordHasher.DEFAULT_COST);
    }

    @Test
    void passwordOf72BytesIsAccepted() {
        String password = "a".repeat(72);

        String hash = hasher.hash(password);

        assertTrue(hasher.verify(password, hash));
    }

    @Test
    void passwordOf73BytesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("a".repeat(73)));
    }

    @Test
    void multibytePasswordOf72BytesIsAccepted() {
        assertEquals(72, EURO_72_BYTES.getBytes(StandardCharsets.UTF_8).length);

        String hash = hasher.hash(EURO_72_BYTES);

        assertTrue(hasher.verify(EURO_72_BYTES, hash));
    }

    @Test
    void multibytePasswordOf73BytesIsRefusedThoughShort() {
        String password = EURO_72_BYTES + "a"; // 25 caracteres, 73 bytes

        assertThrows(IllegalArgumentException.class, () -> hasher.hash(password));
    }

    @Test
    void lastByteOf72Counts() {
        // Sem truncar: mudar o 72º byte muda o resultado.
        String hash = hasher.hash("a".repeat(71) + "b");

        assertFalse(hasher.verify("a".repeat(72), hash));
    }

    @Test
    void refusalMessageDoesNotContainThePassword() {
        String password = "segredo-".repeat(10);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> hasher.hash(password));

        assertFalse(e.getMessage().contains("segredo"));
    }

    @Test
    void verifyOfTooLongOrMissingPasswordIsFalse() {
        String hash = hasher.hash("a".repeat(72));

        assertFalse(hasher.verify("a".repeat(73), hash));
        assertFalse(hasher.verify(null, hash));
        assertFalse(hasher.verify("a".repeat(72), null));
    }
}
