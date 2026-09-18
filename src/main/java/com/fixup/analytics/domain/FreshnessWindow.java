package com.fixup.analytics.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Marca de frescura de la caché. Dentro de la ventana el valor guardado se considera vigente y
 * no se molesta a la fuente externa; fuera de ella, el valor solo sirve para degradar.
 */
public record FreshnessWindow(Duration duration) {

    public FreshnessWindow {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("The freshness window must be positive");
        }
    }

    public boolean isFresh(Instant observedAt, Instant now) {
        return !observedAt.plus(duration).isBefore(now);
    }
}
