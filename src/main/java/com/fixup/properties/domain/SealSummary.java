package com.fixup.properties.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * FR-UC-07: el sello Fix&Rent identifica inmuebles mantenidos por técnicos verificados de la
 * plataforma. Se otorga con al menos un trabajo terminado en los últimos 24 meses y, si esos
 * trabajos fueron calificados, con un promedio de 4 o más. Ninguna persona lo asigna a mano.
 */
public record SealSummary(int recentCompletedWorks, Double averageRating, Instant lastWorkAt) {
    public static final Duration WINDOW = Duration.ofDays(730);
    public static final double MINIMUM_AVERAGE = 4.0;
    public static final SealSummary NONE = new SealSummary(0, null, null);

    public boolean granted() {
        return recentCompletedWorks > 0 && (averageRating == null || averageRating >= MINIMUM_AVERAGE);
    }
}
