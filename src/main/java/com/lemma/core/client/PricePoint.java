package com.lemma.core.client;

import java.math.BigDecimal;
import java.time.Instant;

/** Um ponto da série do CT18: instante e preço exato em USD, já conferidos pelo cliente. */
public record PricePoint(Instant timestamp, BigDecimal price) {
}
