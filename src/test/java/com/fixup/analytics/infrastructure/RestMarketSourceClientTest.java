package com.fixup.analytics.infrastructure;

import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-UC-15: validación estricta del proveedor. Todo payload incompleto o que viole
 * restricciones de dominio o persistencia se convierte a MarketSourceUnavailableException,
 * nunca a 400.
 */
class RestMarketSourceClientTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant OBSERVED_AT = NOW.minus(Duration.ofMinutes(30));

    private static RestMarketSourceClient.ProviderPayload payload(BigDecimal price,
            BigDecimal variation, Integer days, Instant observedAt) {
        return new RestMarketSourceClient.ProviderPayload(price, variation, days, observedAt);
    }

    @Test
    void aCompleteAndValidPayloadBecomesAnIndicatorFromTheExternalProvider() {
        var indicators = RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.00"), new BigDecimal("3.20"), 45, OBSERVED_AT), NOW);

        assertThat(indicators.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(indicators.source()).isEqualTo(IndicatorSource.EXTERNAL_PROVIDER);
        assertThat(indicators.pricePerSquareMeter()).isEqualTo(new BigDecimal("5000000.00"));
        assertThat(indicators.averageDaysOnMarket()).isEqualTo(45);
    }

    @Test
    void aPayloadWithoutObservedAtIsInvalid() {
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.00"), new BigDecimal("3.20"), 45, null), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class)
                .hasMessageContaining("incomplete payload");
    }

    @Test
    void aPayloadWithoutAverageDaysOnMarketIsInvalid() {
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.00"), new BigDecimal("3.20"), null, OBSERVED_AT), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class)
                .hasMessageContaining("incomplete payload");
    }

    @Test
    void aPayloadWithoutPriceOrVariationOrNullPayloadIsInvalid() {
        for (var incomplete : new RestMarketSourceClient.ProviderPayload[]{
                null,
                payload(null, new BigDecimal("3.20"), 45, OBSERVED_AT),
                payload(new BigDecimal("5000000.00"), null, 45, OBSERVED_AT)}) {
            assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO", incomplete, NOW))
                    .isInstanceOf(MarketSourceUnavailableException.class);
        }
    }

    @Test
    void negativeOrZeroPriceIsInvalidAndConvertedToUnavailable() {
        for (BigDecimal invalidPrice : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("-1.00"), new BigDecimal("-5000000.00")}) {
            assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                    payload(invalidPrice, new BigDecimal("3.20"), 45, OBSERVED_AT), NOW))
                    .isInstanceOf(MarketSourceUnavailableException.class)
                    .hasMessageContaining("invalid payload");
        }
    }

    @Test
    void negativeDaysOnMarketIsInvalidAndConvertedToUnavailable() {
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.00"), new BigDecimal("3.20"), -1, OBSERVED_AT), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class)
                .hasMessageContaining("invalid payload");
    }

    @Test
    void numbersWithTooManyDecimalsAreRejectedWithoutAlteringTheValue() {
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.123"), new BigDecimal("3.20"), 45, OBSERVED_AT), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class);

        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.00"), new BigDecimal("3.205"), 45, OBSERVED_AT), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class);
    }

    @Test
    void numbersExceedingDatabaseLimitsAreRejected() {
        BigDecimal hugePrice = new BigDecimal("10000000000000.00"); // 14 integer digits exceeds 13 digits of NUMERIC(15, 2)
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(hugePrice, new BigDecimal("3.20"), 45, OBSERVED_AT), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class);

        BigDecimal hugeVariation = new BigDecimal("10000.00"); // exceeds NUMERIC(6, 2)
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.00"), hugeVariation, 45, OBSERVED_AT), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class);
    }

    @Test
    void futureObservedAtIsRejected() {
        Instant farFuture = NOW.plus(Duration.ofHours(1));
        assertThatThrownBy(() -> RestMarketSourceClient.indicatorsOf("CHAPINERO",
                payload(new BigDecimal("5000000.00"), new BigDecimal("3.20"), 45, farFuture), NOW))
                .isInstanceOf(MarketSourceUnavailableException.class)
                .hasMessageContaining("invalid payload");
    }
}
