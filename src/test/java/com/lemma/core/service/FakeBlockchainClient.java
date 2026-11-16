package com.lemma.core.service;

import com.lemma.core.client.BalanceResult;
import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.BlockchainUnavailableException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blockchain Service falso para os testes: devolve endereços em sequência,
 * saldos configurados por endereço (zero se não configurado) ou falha.
 */
public class FakeBlockchainClient implements BlockchainClient {

    /** Faz o CT4 falhar. */
    public boolean fail;
    public int createWalletCalls;

    /** Saldo por endereço no CT7; endereço ausente tem saldo zero, como uma carteira nova. */
    public final Map<String, BigDecimal> balances = new HashMap<>();
    /** Faz o CT7 falhar (separado do CT4: o cadastro funciona e o saldo falha). */
    public boolean failGetBalance;
    /** Endereços consultados no CT7, na ordem. */
    public final List<String> balanceQueries = new ArrayList<>();

    @Override
    public String createWallet() {
        createWalletCalls++;
        if (fail) {
            throw new BlockchainUnavailableException("CT4 createWallet");
        }
        return address(createWalletCalls);
    }

    @Override
    public BalanceResult getBalance(String ethereumAddress) {
        balanceQueries.add(ethereumAddress);
        if (failGetBalance) {
            throw new BlockchainUnavailableException("CT7 getWalletBalance");
        }
        return new BalanceResult(balances.getOrDefault(ethereumAddress, BigDecimal.ZERO), "SEPOLIA");
    }

    /** Endereço previsível: 0x seguido de 40 hexadecimais terminados no número da chamada. */
    public static String address(int n) {
        return "0x" + String.format("%040x", n);
    }
}
