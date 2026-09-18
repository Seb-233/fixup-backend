package com.fixup.analytics.application;

import com.fixup.analytics.api.IndicatorFreshness;
import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.domain.MarketIndicators;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lo que se le dice al cliente, incluida la procedencia del dato. observedAt viaja siempre para
 * que la interfaz pueda mostrar de cuándo es el indicador y no solo si está degradado.
 *
 * <p>Frescura y fuente son independientes y ambas viajan: LIVE dice que el valor se obtuvo en esta
 * petición, source dice de quién se obtuvo. Un valor sintético recién generado es LIVE y
 * DEVELOPMENT_SYNTHETIC a la vez, y el cliente debe poder distinguirlo de una observación real.
 */
public record MarketIndicatorsView(String zone, BigDecimal pricePerSquareMeter,
        BigDecimal yearOverYearVariationPercent, int averageDaysOnMarket, Instant observedAt,
        IndicatorFreshness freshness, IndicatorSource source) {

    static MarketIndicatorsView of(MarketIndicators indicators, IndicatorFreshness freshness) {
        return new MarketIndicatorsView(indicators.zone(), indicators.pricePerSquareMeter(),
                indicators.yearOverYearVariationPercent(), indicators.averageDaysOnMarket(),
                indicators.observedAt(), freshness, indicators.source());
    }

    /** Atajo para el cliente: el dato no proviene de una consulta viva ni de una caché vigente. */
    public boolean degraded() {
        return freshness == IndicatorFreshness.DEGRADED;
    }

    /** Atajo para el cliente: el número no representa el mercado, viene del respaldo de desarrollo. */
    public boolean synthetic() {
        return source == IndicatorSource.DEVELOPMENT_SYNTHETIC;
    }
}
