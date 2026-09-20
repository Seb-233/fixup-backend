package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicators;

/**
 * Un único intento contra la fuente externa, sin reintentos. TimeoutAndRetryMarketSource es
 * quien decide cuántas veces se llama a esto.
 */
interface MarketSourceClient {
    MarketIndicators fetchOnce(String zone);

    /** Identifica la implementación activa en logs y en el endpoint, sin exponer credenciales. */
    String describe();
}
