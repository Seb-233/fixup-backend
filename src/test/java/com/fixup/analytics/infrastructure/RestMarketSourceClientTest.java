package com.fixup.analytics.infrastructure;

import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-UC-15: lo que el proveedor no entrega no se rellena desde el backend. Un observedAt puesto
 * por nosotros haría pasar un dato de antigüedad desconocida por uno recién observado.
 */
class RestMarketSourceClientTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-18T09:00:00Z");

    private static RestMarketSourceClient.ProviderPayload payload(BigDecimal price,
            BigDecimal variation, Instant observedAt) {
        return new RestMarketSourceClient.ProviderPayload(price, variation, 45, observedAt);
    }

    @Test
    void aCompletePayloadBecomesAnIndicatorFromTheExternalProvider() {
        var indicators = RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(3.2), OBSERVED_AT));

        assertThat(indicators.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(indicators.source()).isEqualTo(IndicatorSource.EXTERNAL_PROVIDER);
    }

    @Test
    void aPayloadWithoutObservedAtIsInvalidAndIsNotCompletedWithTheCurrentInstant() {
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(3.2), null)))
                .isInstanceOf(MarketSourceUnavailableException.class)
                .hasMessageContaining("incomplete payload");
    }

    @Test
    void aPayloadWithoutPriceOrVariationOrNoPayloadAtAllIsInvalidToo() {
        for (var incomplete : new RestMarketSourceClient.ProviderPayload[]{
                null,
                payload(null, BigDecimal.valueOf(3.2), OBSERVED_AT),
                payload(BigDecimal.valueOf(5_000_000), null, OBSERVED_AT)}) {
            assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO", incomplete))
                    .isInstanceOf(MarketSourceUnavailableException.class);
        }
    }
}
