package com.lemma.core.service;

import com.lemma.core.client.AddressHistoryClient;
import com.lemma.core.client.AddressTransaction;
import com.lemma.core.client.BalanceResult;
import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.BlockchainUnavailableException;
import com.lemma.core.client.HistoryUnavailableException;
import com.lemma.core.dto.BalanceResponse;
import com.lemma.core.dto.WalletTransactionResponse;
import com.lemma.core.dto.WalletTransactionsResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.User;
import com.lemma.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Carteira do usuário do token: saldo (CT6, seção 4.1 do desenho) e histórico
 * (CT26, seção 7.2).
 *
 * Ordem: ler o usuário → CT7 ou CT24 → montar a resposta. O endereço vem sempre
 * do app_user, nunca do cliente. Usuário que não existe mais (token de um usuário
 * removido) recebe a mesma 401 do TokenFilter, sem chamar o outro módulo.
 *
 * Log só nas falhas do banco (a classe da exceção); a falha do CT7 ou do CT24 já
 * foi ao log pelo cliente. Nada no sucesso.
 */
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private static final String NETWORK = "SEPOLIA";

    private final UserRepository userRepository;
    private final BlockchainClient blockchainClient;
    private final AddressHistoryClient addressHistoryClient;

    public WalletService(UserRepository userRepository, BlockchainClient blockchainClient,
                         AddressHistoryClient addressHistoryClient) {
        this.userRepository = userRepository;
        this.blockchainClient = blockchainClient;
        this.addressHistoryClient = addressHistoryClient;
    }

    public BalanceResponse getBalance(long userId) {
        User user = findUser(userId, "Saldo");

        BalanceResult balance;
        try {
            balance = blockchainClient.getBalance(user.getEthereumAddress());
        } catch (BlockchainUnavailableException e) {
            // O motivo já foi ao log pelo cliente.
            throw new ApiException(502, "BLOCKCHAIN_UNAVAILABLE",
                    "Não foi possível consultar o saldo agora. Tente novamente.");
        }

        return new BalanceResponse(user.getEthereumAddress(), balance.balanceEth(), balance.network());
    }

    /**
     * CT26: as transações mais recentes da carteira, lidas pelo CT24. Qualquer falha
     * do External Data Service vira 502 HISTORY_UNAVAILABLE; nunca lista parcial.
     */
    public WalletTransactionsResponse getTransactions(long userId) {
        User user = findUser(userId, "Histórico");

        List<AddressTransaction> transactions;
        try {
            transactions = addressHistoryClient.fetchAddressTransactions(user.getEthereumAddress());
        } catch (HistoryUnavailableException e) {
            // O motivo já foi ao log pelo cliente.
            throw new ApiException(502, "HISTORY_UNAVAILABLE",
                    "Não foi possível obter o histórico agora. Tente novamente.");
        }

        List<WalletTransactionResponse> items = transactions.stream()
                .map(t -> new WalletTransactionResponse(t.hash(), t.direction(), t.counterpartyAddress(),
                        t.amountEth(), t.timestamp().toString(), t.status()))
                .toList();
        return new WalletTransactionsResponse(user.getEthereumAddress(), NETWORK, items);
    }

    /**
     * A mensagem do banco pode trazer dados do usuário: vai ao log só a classe da
     * exceção, com o nome da operação (Saldo ou Histórico).
     */
    private User findUser(long userId, String operation) {
        Optional<User> user;
        try {
            user = userRepository.findById(userId);
        } catch (RuntimeException e) {
            log.warn("{}: falha ao consultar o banco ({})", operation, e.getClass().getSimpleName());
            throw new ApiException(500, "INTERNAL_ERROR", "Erro interno.");
        }
        return user.orElseThrow(ApiException::tokenInvalid);
    }
}
