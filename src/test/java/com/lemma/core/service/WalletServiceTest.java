package com.lemma.core.service;

import com.lemma.core.LogCapture;
import com.lemma.core.dto.BalanceResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WalletService com repositório em memória e Blockchain Service falso, sem rede
 * nem banco. Dois usuários gravados: o 1 (ana) e o 2 (bia), cada um com a sua carteira.
 */
class WalletServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T14:30:00Z");
    private static final String ANA_ADDRESS = FakeBlockchainClient.address(1);
    private static final String BIA_ADDRESS = FakeBlockchainClient.address(2);

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final FakeBlockchainClient blockchain = new FakeBlockchainClient();
    private final WalletService service = new WalletService(repository, blockchain);

    @BeforeEach
    void saveUsers() {
        repository.save(new User("ana@example.com", "hash-falso-1", ANA_ADDRESS, NOW));
        repository.save(new User("bia@example.com", "hash-falso-2", BIA_ADDRESS, NOW));
        blockchain.balances.put(ANA_ADDRESS, new BigDecimal("0.000000000000000001"));
        blockchain.balances.put(BIA_ADDRESS, new BigDecimal("7.5"));
    }

    // ---------- sucesso ----------

    @Test
    void returnsTheBalanceOfTheTokenUsersWallet() {
        BalanceResponse response = service.getBalance(1);

        assertEquals(new BalanceResponse(ANA_ADDRESS, new BigDecimal("0.000000000000000001"), "SEPOLIA"), response);
    }

    @Test
    void queriesCt7WithTheAddressOfTheTokenUserOnly() {
        service.getBalance(2);

        assertEquals(List.of(BIA_ADDRESS), blockchain.balanceQueries);
    }

    @Test
    void eachUserSeesOnlyTheirOwnBalance() {
        assertEquals(new BigDecimal("0.000000000000000001"), service.getBalance(1).balanceEth());
        assertEquals(new BigDecimal("7.5"), service.getBalance(2).balanceEth());
        assertEquals(BIA_ADDRESS, service.getBalance(2).ethereumAddress());
    }

    @Test
    void successIsNotLogged() {
        try (LogCapture logs = new LogCapture(WalletService.class)) {
            service.getBalance(1);

            assertEquals(0, logs.lines().size());
        }
    }

    // ---------- usuário do token que não existe mais ----------

    @Test
    void unknownUserIs401TokenInvalidWithoutCallingCt7() {
        ApiException e = assertThrows(ApiException.class, () -> service.getBalance(99));

        assertEquals(401, e.getStatus());
        assertEquals("TOKEN_INVALID", e.getCode());
        // A mesma mensagem do TokenFilter.
        assertEquals(ApiException.tokenInvalid().getMessage(), e.getMessage());
        assertEquals("Token ausente, inválido ou expirado.", e.getMessage());
        assertTrue(blockchain.balanceQueries.isEmpty());
    }

    // ---------- falhas ----------

    @Test
    void ct7FailureIs502BlockchainUnavailable() {
        blockchain.failGetBalance = true;

        ApiException e = assertThrows(ApiException.class, () -> service.getBalance(1));

        assertEquals(502, e.getStatus());
        assertEquals("BLOCKCHAIN_UNAVAILABLE", e.getCode());
        assertEquals("Não foi possível consultar o saldo agora. Tente novamente.", e.getMessage());
    }

    @Test
    void ct7FailureIsNotLoggedAgainByTheService() {
        blockchain.failGetBalance = true;

        try (LogCapture logs = new LogCapture(WalletService.class)) {
            assertThrows(ApiException.class, () -> service.getBalance(1));

            assertEquals(0, logs.lines().size());
        }
    }

    @Test
    void databaseFailureIs500AndOnlyTheExceptionClassIsLogged() {
        repository.failOnFindById = new IllegalStateException("jdbc:mysql://core-db ana@example.com detalhe-do-banco");

        try (LogCapture logs = new LogCapture(WalletService.class)) {
            ApiException e = assertThrows(ApiException.class, () -> service.getBalance(1));

            assertEquals(500, e.getStatus());
            assertEquals("INTERNAL_ERROR", e.getCode());
            assertEquals("WARN Saldo: falha ao consultar o banco (IllegalStateException)", logs.all());
            assertFalse(logs.all().contains("detalhe-do-banco"));
            assertFalse(logs.all().contains("ana@example.com"));
        }
        assertTrue(blockchain.balanceQueries.isEmpty());
    }

    @Test
    void databaseFailureDoesNotLeakTheExceptionMessageToTheResponse() {
        repository.failOnFindById = new IllegalStateException("detalhe-do-banco");

        ApiException e = assertThrows(ApiException.class, () -> service.getBalance(1));

        assertEquals("Erro interno.", e.getMessage());
    }
}
