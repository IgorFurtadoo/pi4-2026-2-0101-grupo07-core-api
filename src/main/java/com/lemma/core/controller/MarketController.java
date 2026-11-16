package com.lemma.core.controller;

import com.lemma.core.service.MarketService;
import io.javalin.http.Context;

/**
 * Rota de mercado, protegida pelo TokenFilter (fora de Main.PUBLIC_ROUTES). Sem
 * regra de negócio: passa o assetId do caminho ao service e devolve o DTO.
 */
public class MarketController {

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    /** CT17: GET /market/{assetId} → 200 { assetId, currency, currentPrice, series }. */
    public void get(Context ctx) {
        ctx.json(marketService.getMarketData(ctx.pathParam("assetId")));
    }
}
