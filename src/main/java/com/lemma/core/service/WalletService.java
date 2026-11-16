package com.lemma.core.service;

import com.lemma.core.client.BalanceResult;
import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.BlockchainUnavailableException;
import com.lemma.core.dto.BalanceResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.User;
import com.lemma.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Saldo da carteira do usuário do token (CT6, seção 4.1 do desenho).
 *
 * Ordem: ler o usuário → CT7 → montar a resposta. O endereço vem sempre do
 * app_user, nunca do cliente. Usuário que não existe mais (token de um usuário
 * removido) recebe a mesma 401 do TokenFilter, sem chamar o CT7.
 *
 * Log só nas falhas do banco (a classe da exceção); a falha do CT7 já foi ao log
 * pelo cliente. Nada no sucesso.
 */
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final UserRepository userRepository;
    private final BlockchainClient blockchainClient;

    public WalletService(UserRepository userRepository, BlockchainClient blockchainClient) {
        this.userRepository = userRepository;
        this.blockchainClient = blockchainClient;
    }

    public BalanceResponse getBalance(long userId) {
        User user = findUser(userId);

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

    /** A mensagem do banco pode trazer dados do usuário: vai ao log só a classe da exceção. */
    private User findUser(long userId) {
        Optional<User> user;
        try {
            user = userRepository.findById(userId);
        } catch (RuntimeException e) {
            log.warn("Saldo: falha ao consultar o banco ({})", e.getClass().getSimpleName());
            throw new ApiException(500, "INTERNAL_ERROR", "Erro interno.");
        }
        return user.orElseThrow(ApiException::tokenInvalid);
    }
}
