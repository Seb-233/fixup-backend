package com.fixup.analytics.infrastructure;

import com.fixup.analytics.api.IndicatorSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DevelopmentMarketSourceClientTest {

    @Test
    void generatesValidIndicatorsForStandardZones() {
        var client = new DevelopmentMarketSourceClient();
        var indicators = client.fetchOnce("CHAPINERO");

        assertThat(indicators.zone()).isEqualTo("CHAPINERO");
        assertThat(indicators.pricePerSquareMeter().signum()).isPositive();
        assertThat(indicators.averageDaysOnMarket()).isGreaterThanOrEqualTo(0);
        assertThat(indicators.source()).isEqualTo(IndicatorSource.DEVELOPMENT_SYNTHETIC);
    }

    @Test
    void survivesNegativeHashCodeAndIntegerMinValueWithoutViolatingInvariants() {
        var client = new DevelopmentMarketSourceClient();

        // Ejecutar la lógica extraída directamente con Integer.MIN_VALUE
        var observedAt = java.time.Instant.parse("2026-09-19T10:00:00Z");
        var extremeIndicators = DevelopmentMarketSourceClient.computeIndicators("EXTREME_ZONE", Integer.MIN_VALUE, observedAt);

        assertThat(extremeIndicators.zone()).isEqualTo("EXTREME_ZONE");
        assertThat(extremeIndicators.observedAt()).isEqualTo(observedAt);
        assertThat(extremeIndicators.source()).isEqualTo(IndicatorSource.DEVELOPMENT_SYNTHETIC);
        assertThat(extremeIndicators.pricePerSquareMeter().signum()).isPositive();
        assertThat(extremeIndicators.pricePerSquareMeter().scale()).isEqualTo(2);
        assertThat(extremeIndicators.averageDaysOnMarket()).isGreaterThanOrEqualTo(0);
        assertThat(extremeIndicators.yearOverYearVariationPercent()).isNotNull();
        assertThat(extremeIndicators.yearOverYearVariationPercent().scale()).isEqualTo(2);

        // Probar diversas zonas aleatorias y vacías/raras
        for (String zone : new String[]{"a", "b", "z", "poly-1", "USAQUEN", "SUBA", "KENNEDY"}) {
            assertThatCode(() -> {
                var ind = client.fetchOnce(zone);
                assertThat(ind.pricePerSquareMeter().signum()).isPositive();
                assertThat(ind.averageDaysOnMarket()).isGreaterThanOrEqualTo(0);
            }).doesNotThrowAnyException();
        }
    }
}
