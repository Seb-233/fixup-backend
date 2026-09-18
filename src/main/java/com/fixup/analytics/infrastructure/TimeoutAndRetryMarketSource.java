package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketIndicatorsSource;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
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
 * <p>La espera entre reintentos bloquea el hilo que atiende la petición. Para el alcance académico
 * se acepta, pero acotada: los reintentos y la espera son configurables y además se recortan aquí
 * a {@link #MAX_RETRIES} y {@link #MAX_RETRY_DELAY}, de modo que ninguna configuración pueda
 * inmovilizar un hilo del servidor. Nada de esto ocurre dentro de una transacción: el caso de uso
 * llama a esta fuente fuera de toda transacción y la caché usa transacciones cortas propias.
 *
 * <p>El registro solo lleva zona e intento. Ni la URL del proveedor, ni sus credenciales, ni el
 * detalle de su error salen en los logs.
 *
 * <p>Agotados los reintentos, este adaptador no decide qué hacer: propaga la indisponibilidad y
 * el caso de uso aplica la degradación elegante.
 */
@Component
class TimeoutAndRetryMarketSource implements MarketIndicatorsSource {
    private static final Logger LOG = LoggerFactory.getLogger(TimeoutAndRetryMarketSource.class);

    /** Techo de reintentos: pocos, porque cada uno bloquea el hilo que atiende la petición. */
    static final int MAX_RETRIES = 3;

    /** Techo de la espera entre reintentos, por la misma razón. */
    static final Duration MAX_RETRY_DELAY = Duration.ofMillis(500);

    /** Puede no existir: sin proveedor configurado no se registra ningún cliente. */
    @Nullable
    private final MarketSourceClient client;

    private final MarketSourceProperties properties;

    TimeoutAndRetryMarketSource(@Nullable MarketSourceClient client,
            MarketSourceProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public MarketIndicators fetch(String zone) {
        // Sin proveedor configurado no hay fuente: se informa la indisponibilidad y el caso de uso
        // degrada o responde 503. Nunca se inventa un dato ni se sirve uno sintético a escondidas.
        if (client == null) {
            throw new MarketSourceUnavailableException("No market source is configured");
        }

        int attempts = Math.min(Math.max(0, properties.getMaxRetries()), MAX_RETRIES) + 1;
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
        var configuredDelay = properties.getRetryDelay();
        if (configuredDelay == null || configuredDelay.isZero() || configuredDelay.isNegative()) {
            return;
        }
        var delay = configuredDelay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : configuredDelay;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MarketSourceUnavailableException("Retrying the market source was interrupted",
                    interrupted);
        }
    }
}
