package com.lemma.core.dto;

import java.math.BigDecimal;

/**
 * Ponto da série do CT17: timestamp em ISO 8601 UTC (Instant.toString do instante
 * recebido) e preço exato em USD, escrito como número JSON em notação plana (JsonConfig).
 */
public record PricePointResponse(String timestamp, BigDecimal price) {
}
