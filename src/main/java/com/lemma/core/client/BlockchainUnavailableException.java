package com.lemma.core.client;

/**
 * O Blockchain Service não respondeu o esperado: tempo esgotado, conexão recusada,
 * 401, 5xx, corpo ilegível ou campo ausente. Quem chama decide o código para o
 * usuário (no cadastro, 502 WALLET_UNAVAILABLE). O motivo já foi para o log pelo
 * cliente; a mensagem é fixa e não traz URL, chave nem corpo.
 */
public class BlockchainUnavailableException extends RuntimeException {

    public BlockchainUnavailableException(String contract) {
        super(contract + " falhou");
    }
}
