package com.fixup.analytics.application;

import com.fixup.analytics.api.IndicatorFreshness;
import com.fixup.analytics.api.MarketIndicatorsUnavailableException;
import com.fixup.analytics.domain.FreshnessWindow;
import com.fixup.analytics.domain.MarketIndicatorSnapshots;
import com.fixup.analytics.domain.MarketIndicators;
import com.fixup.analytics.domain.MarketIndicatorsSource;
import com.fixup.analytics.domain.MarketSourceUnavailableException;
import com.fixup.analytics.domain.Zone;
import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-15: consulta de indicadores del mercado inmobiliario por zona.
 *
 * <p>Aplica la táctica de disponibilidad "Graceful degradation (degradación elegante)" acordada
 * el 25-ago. El orden es deliberado:
 *
 * <ol>
 *   <li>Si la caché está dentro de la ventana de frescura se responde con ella y no se contacta
 *       la fuente externa: menos dependencia de un tercero que puede caerse.</li>
 *   <li>Si no, se consulta la fuente, que ya viene envuelta en Timeout y Retry.</li>
 *   <li>Si la fuente falla y existe un último valor conocido, se responde ese valor marcado como
 *       DEGRADED. El servicio sigue disponible con capacidad reducida, que es exactamente lo que
 *       define la degradación elegante.</li>
 *   <li>Si la fuente falla y no hay nada guardado no se inventa un dato: se informa la
 *       indisponibilidad.</li>
 * </ol>
 */
@Service
public class GetMarketIndicators {
    private static final Logger LOG = LoggerFactory.getLogger(GetMarketIndicators.class);

    private final MarketIndicatorsSource source;
    private final MarketIndicatorSnapshots snapshots;
    private final FreshnessWindow freshnessWindow;

    GetMarketIndicators(MarketIndicatorsSource source, MarketIndicatorSnapshots snapshots,
            FreshnessWindow freshnessWindow) {
        this.source = source;
        this.snapshots = snapshots;
        this.freshnessWindow = freshnessWindow;
    }

    @Transactional
    public MarketIndicatorsView execute(CurrentActor actor, String requestedZone) {
        // Cualquier cuenta activa puede consultar indicadores: no hay restricción por rol, pero
        // resolver el actor ya exige que la cuenta exista y esté ACTIVE en PostgreSQL.
        java.util.Objects.requireNonNull(actor, "actor");
        var zone = Zone.normalize(requestedZone);
        var now = Instant.now();
        Optional<MarketIndicators> cached = snapshots.findByZone(zone);

        if (cached.isPresent() && freshnessWindow.isFresh(cached.get().observedAt(), now)) {
            return MarketIndicatorsView.of(cached.get(), IndicatorFreshness.CACHED);
        }

        try {
            var fresh = source.fetch(zone);
            snapshots.save(fresh);
            return MarketIndicatorsView.of(fresh, IndicatorFreshness.LIVE);
        } catch (MarketSourceUnavailableException unavailable) {
            // No se registra la causa con detalle del proveedor: solo el hecho y la zona.
            LOG.warn("Market source unavailable for zone {}; falling back to the last known value", zone);
            return cached.map(value -> MarketIndicatorsView.of(value, IndicatorFreshness.DEGRADED))
                    .orElseThrow(() -> new MarketIndicatorsUnavailableException(zone));
        }
    }
}
