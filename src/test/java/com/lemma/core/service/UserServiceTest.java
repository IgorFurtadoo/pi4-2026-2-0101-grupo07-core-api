package com.lemma.core.service;

import com.lemma.core.LogCapture;
import com.lemma.core.dto.RegisterUserResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.User;
import com.lemma.core.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cadastro (CT1) com Blockchain Service falso e repository em memória.
 * bcrypt com custo 4 para o teste ser rápido.
 */
class UserServiceTest {

    private static final String PASSWORD = "Senha@12345";

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final FakeBlockchainClient blockchain = new FakeBlockchainClient();
    private final PasswordHasher hasher = new PasswordHasher(4);
    private final UserService service = new UserService(repository, hasher, blockchain);

    private static void assertApiError(int status, String code, Executable call) {
        ApiException e = assertThrows(ApiException.class, call);
        assertEquals(status, e.getStatus());
        assertEquals(code, e.getCode());
    }

    /** E-mail válido com exatamente n caracteres: a...a@example.com. */
    private static String emailWithLength(int n) {
        String domain = "@example.com";
        return "a".repeat(n - domain.length()) + domain;
    }

    // ---------- caminho feliz ----------

    @Test
    void registersUserWithWalletAndHashedPassword() {
        RegisterUserResponse response = service.register("ana@example.com", PASSWORD);

        assertEquals(1L, response.userId());
        assertEquals("ana@example.com", response.email());
        assertEquals(FakeBlockchainClient.address(1), response.ethereumAddress());
        assertEquals(1, blockchain.createWalletCalls);

        User saved = repository.saved.get(0);
        assertEquals("ana@example.com", saved.getEmail());
        assertEquals(FakeBlockchainClient.address(1), saved.getEthereumAddress());
        assertTrue(saved.getPasswordHash().startsWith("$2a$04$"));
        assertTrue(hasher.verify(PASSWORD, saved.getPasswordHash()));
        assertNotNull(saved.getCreatedAt());
        assertEquals(0, saved.getCreatedAt().getNano() % 1_000_000, "createdAt truncado em milissegundos");
    }

    @Test
    void emailIsNormalizedBeforeSaving() {
        RegisterUserResponse response = service.register("  Ana@Example.COM \t", PASSWORD);

        assertEquals("ana@example.com", response.email());
        assertEquals("ana@example.com", repository.saved.get(0).getEmail());
    }

    // ---------- e-mail repetido ----------

    @Test
    void repeatedEmailIs409WithoutCallingCt4() {
        service.register("ana@example.com", PASSWORD);

        assertApiError(409, "EMAIL_ALREADY_REGISTERED", () -> service.register("ana@example.com", PASSWORD));
        assertEquals(1, blockchain.createWalletCalls);
        assertEquals(1, repository.saved.size());
    }

    @Test
    void sameEmailWithOtherCaseAndSpacesIsRepeated() {
        service.register("ana@example.com", PASSWORD);

        assertApiError(409, "EMAIL_ALREADY_REGISTERED", () -> service.register("  ANA@example.Com  ", PASSWORD));
        assertEquals(1, blockchain.createWalletCalls);
    }

    @Test
    void uniqueViolationOnSaveIs409AndOrphanWalletIsLogged() {
        service.register("ana@example.com", PASSWORD);
        repository.hideFromFind = true; // a conferência prévia não vê o outro cadastro (corrida)

        try (LogCapture logs = new LogCapture(UserService.class)) {
            assertApiError(409, "EMAIL_ALREADY_REGISTERED", () -> service.register("ana@example.com", PASSWORD));

            assertEquals(2, blockchain.createWalletCalls);
            assertEquals(1, repository.saved.size());
            assertTrue(logs.all().contains("WARN Carteira órfã " + FakeBlockchainClient.address(2)), logs.all());
            assertFalse(logs.all().contains("ana@example.com"));
        }
    }

    // ---------- falhas do CT4 e do banco ----------

    @Test
    void walletFailureIs502AndNothingIsSaved() {
        blockchain.fail = true;

        assertApiError(502, "WALLET_UNAVAILABLE", () -> service.register("ana@example.com", PASSWORD));
        assertEquals(1, blockchain.createWalletCalls);
        assertEquals(0, repository.saveCalls);
        assertTrue(repository.saved.isEmpty());
    }

    @Test
    void saveFailureAfterCt4Is500AndAddressIsLogged() {
        repository.failOnSave = new IllegalStateException("Duplicate entry 'ana@example.com' (mensagem do banco)");

        try (LogCapture logs = new LogCapture(UserService.class)) {
            assertApiError(500, "INTERNAL_ERROR", () -> service.register("ana@example.com", PASSWORD));

            String expected = "WARN Carteira órfã " + FakeBlockchainClient.address(1)
                    + ": falha ao gravar o usuário (IllegalStateException)";
            assertEquals(expected, logs.all());
            assertFalse(logs.all().contains("ana@example.com"));
            assertFalse(logs.all().contains(PASSWORD));
        }
        assertEquals(1, blockchain.createWalletCalls);
    }

    @Test
    void lookupFailureIs500WithoutCallingCt4() {
        InMemoryUserRepository broken = new InMemoryUserRepository() {
            @Override
            public Optional<User> findByEmail(String email) {
                throw new IllegalStateException("banco fora");
            }
        };
        UserService brokenService = new UserService(broken, hasher, blockchain);

        assertApiError(500, "INTERNAL_ERROR", () -> brokenService.register("ana@example.com", PASSWORD));
        assertEquals(0, blockchain.createWalletCalls);
    }

    // ---------- validação de e-mail ----------

    private void assertEmailInvalid(String email) {
        assertApiError(400, "EMAIL_INVALID", () -> service.register(email, PASSWORD));
        assertEquals(0, blockchain.createWalletCalls);
        assertEquals(0, repository.saveCalls);
    }

    @Test
    void missingOrBlankEmailIsInvalid() {
        assertEmailInvalid(null);
        assertEmailInvalid("");
        assertEmailInvalid("   ");
    }

    @Test
    void malformedEmailIsInvalid() {
        assertEmailInvalid("ana.example.com");
        assertEmailInvalid("ana@example");
        assertEmailInvalid("ana@@example.com");
        assertEmailInvalid("ana maria@example.com");
        assertEmailInvalid("@example.com");
        assertEmailInvalid("ana@.com");
    }

    @Test
    void emailOf254CharactersIsAccepted() {
        String email = emailWithLength(254);
        assertEquals(254, email.length());

        assertEquals(email, service.register(email, PASSWORD).email());
    }

    @Test
    void emailOf255CharactersIsInvalid() {
        assertEmailInvalid(emailWithLength(255));
    }

    @Test
    void lengthIsCountedAfterTrimming() {
        String email = "  " + emailWithLength(254) + "  ";

        assertEquals(emailWithLength(254), service.register(email, PASSWORD).email());
    }

    // ---------- validação de senha ----------

    private void assertPasswordInvalid(String password) {
        assertApiError(400, "PASSWORD_INVALID", () -> service.register("ana@example.com", password));
        assertEquals(0, blockchain.createWalletCalls);
        assertEquals(0, repository.saveCalls);
    }

    @Test
    void missingPasswordIsInvalid() {
        assertPasswordInvalid(null);
        assertPasswordInvalid("");
    }

    @Test
    void passwordOf7CharactersIsInvalid() {
        assertPasswordInvalid("a".repeat(7));
    }

    @Test
    void passwordOf8CharactersIsAccepted() {
        service.register("ana@example.com", "a".repeat(8));

        assertEquals(1, repository.saved.size());
    }

    @Test
    void passwordOf72BytesIsAccepted() {
        service.register("ana@example.com", "a".repeat(72));

        assertEquals(1, repository.saved.size());
    }

    @Test
    void passwordOf73BytesIsInvalid() {
        assertPasswordInvalid("a".repeat(73));
    }

    @Test
    void multibytePasswordIsLimitedByBytes() {
        String euro72 = "€".repeat(24); // 24 caracteres, 72 bytes
        assertEquals(72, euro72.getBytes(StandardCharsets.UTF_8).length);

        service.register("ana@example.com", euro72);
        assertEquals(1, repository.saved.size());

        assertApiError(400, "PASSWORD_INVALID", () -> service.register("bia@example.com", euro72 + "a"));
    }

    @Test
    void minimumIsCountedInCharactersNotBytes() {
        // 7 caracteres e 21 bytes: curta demais, mesmo com mais de 8 bytes.
        assertPasswordInvalid("€".repeat(7));
    }

    @Test
    void emailIsValidatedBeforePassword() {
        assertApiError(400, "EMAIL_INVALID", () -> service.register("invalido", "curta"));
    }
}
