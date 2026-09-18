package com.fixup.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * FR-UC-15: indicadores del mercado inmobiliario de una zona.
 *
 * <p>observedAt es el momento en que la fuente externa produjo el dato, no el momento en que se
 * respondió al cliente. De esa distinción depende poder decir si un dato está degradado.
 */
public record MarketIndicators(String zone, BigDecimal pricePerSquareMeter,
        BigDecimal yearOverYearVariationPercent, int averageDaysOnMarket, Instant observedAt) {

    public MarketIndicators {
        zone = Zone.normalize(zone);
        Objects.requireNonNull(pricePerSquareMeter, "pricePerSquareMeter");
        Objects.requireNonNull(yearOverYearVariationPercent, "yearOverYearVariationPercent");
        Objects.requireNonNull(observedAt, "observedAt");
        if (pricePerSquareMeter.signum() <= 0) {
            throw new IllegalArgumentException("The price per square meter must be positive");
        }
        if (averageDaysOnMarket < 0) {
            throw new IllegalArgumentException("The average days on market cannot be negative");
        }
    }
}
