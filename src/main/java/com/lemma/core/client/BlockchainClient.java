package com.lemma.core.client;

import java.math.BigDecimal;

/**
 * Chamadas ao Blockchain Service (seção 7.1 do desenho). É interface só para os
 * testes dos services usarem um Blockchain Service falso, sem rede (seção 4.2).
 * O CT14 entra no passo C6.
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

    /**
     * CT10 requestTransactionSubmission: pede o envio e devolve o taskId da task
     * criada (PENDING). Uma única tentativa: o CT10 não tem idempotência (seção 8.3).
     *
     * @param sourceAddress      endereço guardado no app_user (nunca vindo do cliente)
     * @param destinationAddress destino como o usuário enviou, já no formato 0x + 40 hexadecimais
     * @param amountEth          valor já validado e normalizado pelo service (maior que zero,
     *                           escala de 0 a 18, até 20 dígitos inteiros)
     * @throws BlockchainRejectedException    400 ADDRESS_INVALID ou AMOUNT_INVALID do Blockchain Service
     * @throws BlockchainUnavailableException em qualquer outra resposta que não seja a esperada
     */
    String requestSubmission(String sourceAddress, String destinationAddress, BigDecimal amountEth);
}
