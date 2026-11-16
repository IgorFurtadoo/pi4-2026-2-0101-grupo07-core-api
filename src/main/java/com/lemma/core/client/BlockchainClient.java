package com.lemma.core.client;

/**
 * Chamadas ao Blockchain Service (seção 7.1 do desenho). É interface só para os
 * testes dos services usarem um Blockchain Service falso, sem rede (seção 4.2).
 * Os demais contratos (CT10, CT14) entram nos passos C5 e C6.
 */
public interface BlockchainClient {

    /**
     * CT4 createWallet: cria a carteira custodial e devolve o endereço público.
     *
     * @throws BlockchainUnavailableException em qualquer resposta que não seja a esperada
     */
    String createWallet();

    /**
     * CT7 getWalletBalance: saldo da carteira, consultado na rede pelo Blockchain Service.
     *
     * @param ethereumAddress endereço guardado no app_user (nunca vindo do cliente)
     * @throws BlockchainUnavailableException em qualquer resposta que não seja a esperada,
     *         ou se o endereço estiver fora do formato (a chamada nem é feita)
     */
    BalanceResult getBalance(String ethereumAddress);
}
