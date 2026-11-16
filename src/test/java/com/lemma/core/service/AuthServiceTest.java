package com.lemma.core.service;

import com.lemma.core.LogCapture;
import com.lemma.core.dto.LoginResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.User;
import com.lemma.core.security.JwtService;
import com.lemma.core.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Login (CT3) com repository em memória, bcrypt de custo 4 e relógio fixo.
 * O PasswordHasher que conta chamadas prova que o bcrypt roda também quando o
 * e-mail não existe (hash falso).
 */
class AuthServiceTest {

    private static final String EMAIL = "ana@example.com";
    private static final String PASSWORD = "Senha@12345";
    private static final String SECRET = "segredo-de-teste-falso-com-mais-de-32-bytes";
    private static final Instant T0 = Instant.parse("2026-10-20T14:30:00Z");

    /** PasswordHasher de verdade (custo 4) que conta as chamadas e guarda o último hash conferido. */
    static class CountingHasher extends PasswordHasher {
        int hashCalls;
        final List<String> verifiedHashes = new ArrayList<>();

        CountingHasher() {
            super(4);
        }

        @Override
        public String hash(String password) {
            hashCalls++;
            return super.hash(password);
        }

        @Override
        public boolean verify(String password, String hash) {
            verifiedHashes.add(hash);
            return super.verify(password, hash);
        }
    }

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final CountingHasher hasher = new CountingHasher();
    private final JwtService jwtService = new JwtService(SECRET, 240, Clock.fixed(T0, ZoneOffset.UTC));
    private final AuthService service = new AuthService(repository, hasher, jwtService);

    /** Grava um usuário como o cadastro faria (e-mail normalizado, senha em bcrypt). */
    private User register(String email, String password) {
        User user = new User(email, new PasswordHasher(4).hash(password), FakeBlockchainClient.address(1), T0);
        repository.save(user);
        return user;
    }

    private static ApiException assertApiError(int status, String code, Executable call) {
        ApiException e = assertThrows(ApiException.class, call);
        assertEquals(status, e.getStatus());
        assertEquals(code, e.getCode());
        return e;
    }

    private void assertInvalidCredentials(String email, String password) {
        ApiException e = assertApiError(401, "INVALID_CREDENTIALS", () -> service.login(email, password));
        assertEquals("E-mail ou senha incorretos", e.getMessage());
    }

    // ---------- sucesso ----------

    @Test
    void loginReturnsTokenOfTheUserAndExpiresAt() {
        register(EMAIL, PASSWORD);

        LoginResponse response = service.login(EMAIL, PASSWORD);

        assertEquals(1L, jwtService.verify(response.token()));
        assertEquals("2026-10-20T18:30:00Z", response.expiresAt());
    }

    @Test
    void emailWithUppercaseAndSpacesLogsIn() {
        register(EMAIL, PASSWORD);

        LoginResponse response = service.login("  Ana@Example.COM \t", PASSWORD);

        assertEquals(1L, jwtService.verify(response.token()));
    }

    @Test
    void passwordOfOnlySpacesIsCheckedLikeAnyOther() {
        register(EMAIL, " ".repeat(8)); // o cadastro aceita: 8 caracteres

        assertEquals(1L, jwtService.verify(service.login(EMAIL, " ".repeat(8)).token()));
        assertInvalidCredentials(EMAIL, " ".repeat(3));
    }

    // ---------- falha: mesma resposta ----------

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameException() {
        register(EMAIL, PASSWORD);

        ApiException wrongPassword = assertThrows(ApiException.class, () -> service.login(EMAIL, "Senha@99999"));
        ApiException unknownEmail = assertThrows(ApiException.class,
                () -> service.login("bia@example.com", PASSWORD));

        assertEquals(401, wrongPassword.getStatus());
        assertEquals(wrongPassword.getStatus(), unknownEmail.getStatus());
        assertEquals("INVALID_CREDENTIALS", wrongPassword.getCode());
        assertEquals(wrongPassword.getCode(), unknownEmail.getCode());
        assertEquals("E-mail ou senha incorretos", wrongPassword.getMessage());
        assertEquals(wrongPassword.getMessage(), unknownEmail.getMessage());
    }

    @Test
    void unknownEmailStillRunsBcryptAgainstADummyHashOfTheSameCost() {
        assertEquals(1, hasher.hashCalls, "o hash falso é gerado uma vez, na construção");

        assertInvalidCredentials("bia@example.com", PASSWORD);

        assertEquals(1, hasher.verifiedHashes.size());
        String dummy = hasher.verifiedHashes.get(0);
        assertTrue(dummy.startsWith("$2a$04$"), "mesmo custo do PasswordHasher injetado");
        assertEquals(60, dummy.length());
    }

    @Test
    void wrongPasswordRunsBcryptAgainstTheUserHash() {
        User ana = register(EMAIL, PASSWORD);

        assertInvalidCredentials(EMAIL, "Senha@99999");

        assertEquals(List.of(ana.getPasswordHash()), hasher.verifiedHashes);
    }

    @Test
    void dummyHashIsTheSameForEveryUnknownEmail() {
        assertInvalidCredentials("bia@example.com", PASSWORD);
        assertInvalidCredentials("carla@example.com", "outra-senha");

        assertEquals(2, hasher.verifiedHashes.size());
        assertEquals(hasher.verifiedHashes.get(0), hasher.verifiedHashes.get(1));
        assertEquals(1, hasher.hashCalls, "nenhum bcrypt novo por requisição");
    }

    @Test
    void dummyHashIsDifferentOnEachStart() {
        CountingHasher other = new CountingHasher();
        AuthService otherService = new AuthService(repository, other, jwtService);

        assertInvalidCredentials("bia@example.com", PASSWORD);
        assertThrows(ApiException.class, () -> otherService.login("bia@example.com", PASSWORD));

        assertNotEquals(hasher.verifiedHashes.get(0), other.verifiedHashes.get(0));
    }

    @Test
    void passwordOf73BytesIsInvalidCredentialsNotPasswordInvalid() {
        register(EMAIL, "a".repeat(72));

        assertInvalidCredentials(EMAIL, "a".repeat(73));
        assertInvalidCredentials(EMAIL, "€".repeat(24) + "a");
    }

    @Test
    void malformedEmailIsInvalidCredentialsNotEmailInvalid() {
        assertInvalidCredentials("sem-arroba", PASSWORD);
    }

    // ---------- campos ausentes ou vazios ----------

    @Test
    void missingOrEmptyFieldsAreRequestInvalidWithoutQueryingTheDatabase() {
        InMemoryUserRepository untouchable = new InMemoryUserRepository() {
            @Override
            public Optional<User> findByEmail(String email) {
                return fail("não deveria consultar o banco");
            }
        };
        AuthService guarded = new AuthService(untouchable, hasher, jwtService);

        String[][] cases = {
                {null, PASSWORD}, {"", PASSWORD}, {"   ", PASSWORD}, {" \t ", PASSWORD},
                {EMAIL, null}, {EMAIL, ""}, {null, null}};
        for (String[] c : cases) {
            ApiException e = assertApiError(400, "REQUEST_INVALID", () -> guarded.login(c[0], c[1]));
            assertEquals("Corpo da requisição inválido.", e.getMessage());
        }
        assertTrue(hasher.verifiedHashes.isEmpty());
    }

    // ---------- banco ----------

    @Test
    void databaseFailureIs500AndTheLogHasNoEmail() {
        InMemoryUserRepository broken = new InMemoryUserRepository() {
            @Override
            public Optional<User> findByEmail(String email) {
                throw new IllegalStateException("Communications link failure ao buscar " + email);
            }
        };
        AuthService brokenService = new AuthService(broken, hasher, jwtService);

        try (LogCapture logs = new LogCapture(AuthService.class)) {
            ApiException e = assertApiError(500, "INTERNAL_ERROR", () -> brokenService.login(EMAIL, PASSWORD));

            assertEquals("Erro interno.", e.getMessage());
            assertEquals("WARN Login: falha ao consultar o banco (IllegalStateException)", logs.all());
            assertFalse(logs.all().contains(EMAIL));
        }
        assertTrue(hasher.verifiedHashes.isEmpty());
    }

    // ---------- log ----------

    @Test
    void successLogHasOnlyTheUserId() {
        register(EMAIL, PASSWORD);

        try (LogCapture logs = new LogCapture(AuthService.class)) {
            LoginResponse response = service.login(EMAIL, PASSWORD);

            assertEquals("INFO Usuário 1 autenticado", logs.all());
            assertFalse(logs.all().contains(response.token()));
        }
    }

    @Test
    void failedLoginsLogNothing() {
        register(EMAIL, PASSWORD);

        try (LogCapture logs = new LogCapture(AuthService.class)) {
            assertThrows(ApiException.class, () -> service.login(EMAIL, "Senha@99999"));
            assertThrows(ApiException.class, () -> service.login("bia@example.com", PASSWORD));
            assertThrows(ApiException.class, () -> service.login(EMAIL, "a".repeat(73)));
            assertThrows(ApiException.class, () -> service.login("", PASSWORD));

            assertEquals(List.of(), logs.lines());
        }
    }

    @Test
    void loginResponseToStringHidesTheToken() {
        register(EMAIL, PASSWORD);

        LoginResponse response = service.login(EMAIL, PASSWORD);

        assertFalse(response.toString().contains(response.token()));
    }
}
