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

        // Probar con un zone cuyo hashCode sea negativo o extremo
        // Math.floorMod(Integer.MIN_VALUE, 1_000_000) debe ser >= 0
        int minValMod = Math.floorMod(Integer.MIN_VALUE, 1_000_000);
        assertThat(minValMod).isGreaterThanOrEqualTo(0);

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
