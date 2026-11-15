package com.lemma.core.service;

import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.BlockchainUnavailableException;

/** Blockchain Service falso para os testes: devolve endereços em sequência ou falha. */
public class FakeBlockchainClient implements BlockchainClient {

    public boolean fail;
    public int createWalletCalls;

    @Override
    public String createWallet() {
        createWalletCalls++;
        if (fail) {
            throw new BlockchainUnavailableException("CT4 createWallet");
        }
        return address(createWalletCalls);
    }

    /** Endereço previsível: 0x seguido de 40 hexadecimais terminados no número da chamada. */
    public static String address(int n) {
        return "0x" + String.format("%040x", n);
    }
}
