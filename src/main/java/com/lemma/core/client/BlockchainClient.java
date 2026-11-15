package com.lemma.core.client;

/**
 * Chamadas ao Blockchain Service (seção 7.1 do desenho). É interface só para os
 * testes dos services usarem um Blockchain Service falso, sem rede (seção 4.2).
 * Os demais contratos (CT7, CT10, CT14) entram nos passos C4 a C6.
 */
public interface BlockchainClient {

    /**
     * CT4 createWallet: cria a carteira custodial e devolve o endereço público.
     *
     * @throws BlockchainUnavailableException em qualquer resposta que não seja a esperada
     */
    String createWallet();
}
