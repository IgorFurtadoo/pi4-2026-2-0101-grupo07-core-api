package com.lemma.core.client;

/**
 * O External Data Service não respondeu o esperado no CT24: tempo esgotado,
 * conexão recusada, qualquer status diferente de 200 (inclusive 400, 401 e o 502
 * HISTORY_UNAVAILABLE dele), corpo ilegível ou fora do formato. O service a
 * converte em 502 HISTORY_UNAVAILABLE. O motivo já foi para o log pelo cliente; a
 * mensagem é fixa e não traz URL, chave, corpo nem endereço.
 */
public class HistoryUnavailableException extends RuntimeException {

    public HistoryUnavailableException(String contract) {
        super(contract + " falhou");
    }
}
