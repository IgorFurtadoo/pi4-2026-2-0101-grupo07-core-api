package com.lemma.core.client;

/**
 * O External Data Service não respondeu o esperado: tempo esgotado, conexão
 * recusada, 401, 429, 5xx, 404 sem ASSET_NOT_FOUND, corpo ilegível ou fora do
 * formato. O service a converte em 502 MARKET_DATA_UNAVAILABLE. O motivo já foi
 * para o log pelo cliente; a mensagem é fixa e não traz URL, chave, corpo nem assetId.
 */
public class MarketDataUnavailableException extends RuntimeException {

    public MarketDataUnavailableException(String contract) {
        super(contract + " falhou");
    }
}
