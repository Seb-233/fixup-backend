package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketIndicatorsSource;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tácticas de disponibilidad acordadas el 25-ago, aplicadas a la fuente externa de FR-UC-15:
 *
 * <ul>
 *   <li><b>Timeout</b> (detección de defectos): el cliente HTTP corta la espera y lanza una
 *       excepción en lugar de dejar la petición colgada.</li>
 *   <li><b>Retry / Reintentar</b> (recuperación): se reintenta un máximo preestablecido de veces,
 *       con una frecuencia de reintentos configurable.</li>
 * </ul>
 *
 * <p>Agotados los reintentos, este adaptador no decide qué hacer: propaga la indisponibilidad y
 * el caso de uso aplica la degradación elegante.
 */
@Component
class TimeoutAndRetryMarketSource implements MarketIndicatorsSource {
    private static final Logger LOG = LoggerFactory.getLogger(TimeoutAndRetryMarketSource.class);

    private final MarketSourceClient client;
    private final MarketSourceProperties properties;

    TimeoutAndRetryMarketSource(MarketSourceClient client, MarketSourceProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public MarketIndicators fetch(String zone) {
        int attempts = Math.max(0, properties.getMaxRetries()) + 1;
        MarketSourceUnavailableException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return client.fetchOnce(zone);
            } catch (MarketSourceUnavailableException failure) {
                lastFailure = failure;
                LOG.warn("Market source attempt {}/{} failed for zone {}", attempt, attempts, zone);
                if (attempt < attempts) {
                    pauseBeforeRetry();
                }
            }
        }
        throw new MarketSourceUnavailableException(
                "The market source did not answer after " + attempts + " attempts", lastFailure);
    }

    private void pauseBeforeRetry() {
        var delay = properties.getRetryDelay();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MarketSourceUnavailableException("Retrying the market source was interrupted",
                    interrupted);
        }
    }
}
