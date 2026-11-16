package com.lemma.core.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Saída 200 do CT17 getMarketData. currency é sempre USD; currentPrice e os
 * preços da série saem exatos, como número JSON em notação plana, sem normalizar
 * zeros; a série segue a ordem recebida do External Data Service.
 */
public record MarketDataResponse(String assetId, String currency, BigDecimal currentPrice,
                                 List<PricePointResponse> series) {
}
