package com.fixup.analytics.infrastructure;

import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-UC-15: táctica de recuperación "Retry (Reintentar)" con máximo preestablecido de reintentos
 * y frecuencia configurable, acordada el 25-ago.
 */
class TimeoutAndRetryMarketSourceTest {

    private static MarketSourceProperties properties(int maxRetries) {
        var properties = new MarketSourceProperties();
        properties.setMaxRetries(maxRetries);
        properties.setRetryDelay(Duration.ZERO);
        return properties;
    }

    private static MarketIndicators sample() {
        return new MarketIndicators("CHAPINERO", BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(3.2),
                45, Instant.parse("2026-09-18T09:00:00Z"), IndicatorSource.EXTERNAL_PROVIDER);
    }

    private record CountingClient(AtomicInteger calls, int failuresBeforeSuccess)
            implements MarketSourceClient {

        @Override
        public MarketIndicators fetchOnce(String zone) {
            if (calls.incrementAndGet() <= failuresBeforeSuccess) {
                throw new MarketSourceUnavailableException("synthetic failure");
            }
            return sample();
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    @Test
    void aSourceThatAnswersIsCalledOnlyOnce() {
        var calls = new AtomicInteger();
        var source = new TimeoutAndRetryMarketSource(new CountingClient(calls, 0), properties(2));

        assertThat(source.fetch("CHAPINERO")).isEqualTo(sample());
        assertThat(calls).hasValue(1);
    }

    @Test
    void aTransientFailureIsRetriedAndRecovered() {
        var calls = new AtomicInteger();
        var source = new TimeoutAndRetryMarketSource(new CountingClient(calls, 2), properties(2));

        assertThat(source.fetch("CHAPINERO")).isEqualTo(sample());
        assertThat(calls).hasValue(3);
    }

    @Test
    void theNumberOfAttemptsIsTheConfiguredMaximumPlusTheFirstCall() {
        var calls = new AtomicInteger();
        var source = new TimeoutAndRetryMarketSource(new CountingClient(calls, Integer.MAX_VALUE),
                properties(2));

        assertThatThrownBy(() -> source.fetch("CHAPINERO"))
                .isInstanceOf(MarketSourceUnavailableException.class)
                .hasMessageContaining("3 attempts");
        assertThat(calls).hasValue(3);
    }

    @Test
    void retriesCanBeDisabledAndThenTheSourceIsCalledOnce() {
        var calls = new AtomicInteger();
        var source = new TimeoutAndRetryMarketSource(new CountingClient(calls, Integer.MAX_VALUE),
                properties(0));

        assertThatThrownBy(() -> source.fetch("CHAPINERO"))
                .isInstanceOf(MarketSourceUnavailableException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void theAdapterDoesNotDecideTheFallbackAndPropagatesTheFailure() {
        var source = new TimeoutAndRetryMarketSource(new CountingClient(new AtomicInteger(),
                Integer.MAX_VALUE), properties(1));

        // Degradar es responsabilidad del caso de uso, no del adaptador.
        assertThatThrownBy(() -> source.fetch("CHAPINERO"))
                .isInstanceOf(MarketSourceUnavailableException.class);
    }

    /** Falta de proveedor no puede terminar en un dato inventado: es indisponibilidad. */
    @Test
    void withoutAConfiguredProviderThereIsNoSourceAndItSaysSo() {
        var source = new TimeoutAndRetryMarketSource(null, properties(2));

        assertThatThrownBy(() -> source.fetch("CHAPINERO"))
                .isInstanceOf(MarketSourceUnavailableException.class)
                .hasMessageContaining("No market source is configured");
    }

    /** La espera bloquea el hilo del servidor, así que la configuración tiene un techo. */
    @Test
    void theConfiguredRetriesAndDelayAreCappedSoNoThreadIsHeldForLong() {
        var calls = new AtomicInteger();
        var properties = properties(50);
        properties.setRetryDelay(Duration.ofMinutes(5));
        var source = new TimeoutAndRetryMarketSource(new CountingClient(calls, Integer.MAX_VALUE),
                properties);

        var startedAt = System.nanoTime();
        assertThatThrownBy(() -> source.fetch("CHAPINERO"))
                .isInstanceOf(MarketSourceUnavailableException.class);

        assertThat(calls).hasValue(TimeoutAndRetryMarketSource.MAX_RETRIES + 1);
        // Con la espera configurada sin recortar, esto habría tardado quince minutos.
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void theDevelopmentFallbackIsDeterministicAndClearlyIdentified() {
        var development = new DevelopmentMarketSourceClient();

        var first = development.fetchOnce("CHAPINERO");
        var second = development.fetchOnce("CHAPINERO");

        assertThat(development.describe()).isEqualTo("development-fallback");
        // El dato sintético se identifica como tal desde el origen y no depende de quién lo lea.
        assertThat(first.source()).isEqualTo(IndicatorSource.DEVELOPMENT_SYNTHETIC);
        assertThat(first.pricePerSquareMeter()).isEqualTo(second.pricePerSquareMeter());
        assertThat(first.averageDaysOnMarket()).isEqualTo(second.averageDaysOnMarket());
        assertThat(first.pricePerSquareMeter()).isNotEqualTo(
                development.fetchOnce("USAQUEN").pricePerSquareMeter());
    }
}
