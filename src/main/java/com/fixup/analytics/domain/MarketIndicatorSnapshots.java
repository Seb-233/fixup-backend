package com.fixup.analytics.domain;

import java.util.Optional;

/**
 * Caché en PostgreSQL del último valor conocido por zona. Es lo que permite la degradación
 * elegante cuando la fuente externa no responde.
 */
public interface MarketIndicatorSnapshots {
    Optional<MarketIndicators> findByZone(String zone);

    /**
     * Guarda el snapshot de forma atómica y monotónica si no existe o si {@code indicators.observedAt()}
     * es mayor o igual que el actualmente almacenado.
     *
     * @param indicators indicadores a almacenar
     * @return {@code true} si la escritura fue aceptada (insertada o actualizada); {@code false} si fue
     *         descartada porque ya existía un registro más reciente.
     */
    boolean saveIfNewer(MarketIndicators indicators);

    default void save(MarketIndicators indicators) {
        saveIfNewer(indicators);
    }
}
