package com.fixup.analytics.domain;

import java.util.Optional;

/**
 * Caché en PostgreSQL del último valor conocido por zona. Es lo que permite la degradación
 * elegante cuando la fuente externa no responde.
 */
public interface MarketIndicatorSnapshots {
    Optional<MarketIndicators> findByZone(String zone);

    void save(MarketIndicators indicators);
}
