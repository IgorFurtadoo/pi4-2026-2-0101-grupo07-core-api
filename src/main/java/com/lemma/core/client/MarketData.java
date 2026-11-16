package com.lemma.core.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado do CT18 fetchMarketData, já conferido pelo cliente: assetId igual ao
 * pedido; currency sempre USD; preço atual exato (o mesmo valor e a mesma escala
 * que o External Data Service mandou); série não vazia, na ordem recebida.
 */
public record MarketData(String assetId, String currency, BigDecimal currentPrice,
                         List<PricePoint> series) {
}
