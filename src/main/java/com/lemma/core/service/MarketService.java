package com.lemma.core.service;

import com.lemma.core.client.AssetNotFoundException;
import com.lemma.core.client.MarketData;
import com.lemma.core.client.MarketDataClient;
import com.lemma.core.client.MarketDataUnavailableException;
import com.lemma.core.dto.MarketDataResponse;
import com.lemma.core.dto.PricePointResponse;
import com.lemma.core.exception.ApiException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Dados de mercado (CT17, seção 8.6 do desenho).
 *
 * Ordem: token (TokenFilter) → assetId válido → [limite de requisições, no C8] →
 * CT18 → montar a resposta. assetId fora do formato responde o mesmo 404 do ativo
 * desconhecido, sem chamar o External Data Service.
 *
 * Sem log próprio: a falha do CT18 já foi ao log pelo cliente.
 */
public class MarketService {

    /**
     * assetId válido (seção 6 do desenho), sem normalizar caixa: ETHEREUM é
     * inválido. O MarketDataClient repete o formato para proteger o caminho do CT18.
     */
    static final Pattern ASSET_ID_FORMAT = Pattern.compile("^[a-z0-9-]{1,64}$");

    private final MarketDataClient marketDataClient;

    public MarketService(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    public MarketDataResponse getMarketData(String assetId) {
        if (assetId == null || !ASSET_ID_FORMAT.matcher(assetId).matches()) {
            throw assetNotFound();
        }

        // C8: o limite de requisições entra aqui, depois do assetId e antes do CT18 (seção 8.6).

        MarketData data;
        try {
            data = marketDataClient.fetchMarketData(assetId);
        } catch (AssetNotFoundException e) {
            throw assetNotFound();
        } catch (MarketDataUnavailableException e) {
            // O motivo já foi ao log pelo cliente.
            throw new ApiException(502, "MARKET_DATA_UNAVAILABLE",
                    "Não foi possível obter os dados de mercado agora. Tente novamente.");
        }

        List<PricePointResponse> series = data.series().stream()
                .map(point -> new PricePointResponse(point.timestamp().toString(), point.price()))
                .toList();
        return new MarketDataResponse(data.assetId(), data.currency(), data.currentPrice(), series);
    }

    /** A mesma resposta para assetId fora do formato e para ativo desconhecido. */
    private static ApiException assetNotFound() {
        return new ApiException(404, "ASSET_NOT_FOUND", "Ativo não encontrado.");
    }
}
