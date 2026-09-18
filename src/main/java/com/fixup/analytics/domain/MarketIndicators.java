package com.fixup.analytics.domain;

import com.fixup.analytics.api.IndicatorSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * FR-UC-15: indicadores del mercado inmobiliario de una zona.
 *
 * <p>observedAt es el momento en que la fuente externa produjo el dato, no el momento en que se
 * respondió al cliente. De esa distinción depende poder decir si un dato está degradado. Ninguno
 * de los dos se inventa: un valor sin observedAt no es un indicador y no llega hasta aquí.
 *
 * <p>source viaja con el dato y se guarda en la caché, de modo que un valor sintético sigue
 * identificándose como sintético cuando después se sirve como CACHED o DEGRADED.
 */
public record MarketIndicators(String zone, BigDecimal pricePerSquareMeter,
        BigDecimal yearOverYearVariationPercent, int averageDaysOnMarket, Instant observedAt,
        IndicatorSource source) {

    public MarketIndicators {
        zone = Zone.normalize(zone);
        Objects.requireNonNull(pricePerSquareMeter, "pricePerSquareMeter");
        Objects.requireNonNull(yearOverYearVariationPercent, "yearOverYearVariationPercent");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(source, "source");
        if (pricePerSquareMeter.signum() <= 0) {
            throw new IllegalArgumentException("The price per square meter must be positive");
        }
        if (averageDaysOnMarket < 0) {
            throw new IllegalArgumentException("The average days on market cannot be negative");
        }
    }
}
