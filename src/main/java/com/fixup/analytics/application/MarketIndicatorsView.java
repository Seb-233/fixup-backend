package com.fixup.analytics.application;

import com.fixup.analytics.api.IndicatorFreshness;
import com.fixup.analytics.domain.MarketIndicators;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lo que se le dice al cliente, incluida la procedencia del dato. observedAt viaja siempre para
 * que la interfaz pueda mostrar de cuándo es el indicador y no solo si está degradado.
 */
public record MarketIndicatorsView(String zone, BigDecimal pricePerSquareMeter,
        BigDecimal yearOverYearVariationPercent, int averageDaysOnMarket, Instant observedAt,
        IndicatorFreshness freshness) {

    static MarketIndicatorsView of(MarketIndicators indicators, IndicatorFreshness freshness) {
        return new MarketIndicatorsView(indicators.zone(), indicators.pricePerSquareMeter(),
                indicators.yearOverYearVariationPercent(), indicators.averageDaysOnMarket(),
                indicators.observedAt(), freshness);
    }

    /** Atajo para el cliente: el dato no proviene de una consulta viva ni de una caché vigente. */
    public boolean degraded() {
        return freshness == IndicatorFreshness.DEGRADED;
    }
}
