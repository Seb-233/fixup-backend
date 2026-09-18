package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicators;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RESPALDO DE DESARROLLO. NO ES UNA FUENTE REAL DE MERCADO.
 *
 * <p>El equipo todavía no tiene proveedor de datos inmobiliarios contratado. Esta implementación
 * existe para que FR-UC-15 sea ejecutable y demostrable de extremo a extremo; los números que
 * devuelve son sintéticos y derivados del nombre de la zona, no observaciones del mercado.
 *
 * <p>Es determinista a propósito: la misma zona da siempre el mismo valor, de modo que nadie
 * pueda confundir variación sintética con variación real. Cuando exista proveedor, se configura
 * {@code fixup.analytics.market-source.provider=rest} y esta clase deja de instanciarse.
 */
@Component
@ConditionalOnProperty(name = "fixup.analytics.market-source.provider", havingValue = "development",
        matchIfMissing = true)
class DevelopmentMarketSourceClient implements MarketSourceClient {

    @Override
    public MarketIndicators fetchOnce(String zone) {
        int seed = Math.abs(zone.hashCode());
        var price = BigDecimal.valueOf(3_000_000L + (seed % 4_000) * 1_000L);
        var variation = BigDecimal.valueOf((seed % 210) - 60).divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP);
        int daysOnMarket = 25 + (seed % 120);
        return new MarketIndicators(zone, price, variation, daysOnMarket, Instant.now());
    }

    @Override
    public String describe() {
        return "development-fallback";
    }
}
