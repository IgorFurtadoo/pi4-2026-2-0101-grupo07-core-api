package com.lemma.core.client;

/**
 * O External Data Service respondeu 404 ASSET_NOT_FOUND: o ativo não existe. O
 * service a converte em 404 ASSET_NOT_FOUND. Não vai ao log (usuário pedindo um
 * ativo que não existe é normal); a mensagem é fixa e não traz o assetId.
 */
public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException() {
        super("CT18 fetchMarketData: ativo desconhecido");
    }
}
