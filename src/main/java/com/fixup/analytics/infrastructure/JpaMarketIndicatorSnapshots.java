package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicatorSnapshots;
import com.fixup.analytics.domain.MarketIndicators;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cada operación abre y cierra su propia transacción corta. El caso de uso no envuelve la consulta
 * a PostgreSQL, la llamada HTTP y la escritura en una sola transacción: la llamada externa puede
 * tardar segundos entre reintentos y retendría una conexión sin usarla. REQUIRES_NEW lo deja
 * explícito y protege el límite aunque alguien añada una transacción más arriba.
 */
@Repository
class JpaMarketIndicatorSnapshots implements MarketIndicatorSnapshots {
    private final MarketIndicatorSnapshotJpaRepository repository;

    JpaMarketIndicatorSnapshots(MarketIndicatorSnapshotJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<MarketIndicators> findByZone(String zone) {
        return repository.findById(zone).map(MarketIndicatorSnapshotEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(MarketIndicators indicators) {
        var entity = repository.findById(indicators.zone())
                .orElseGet(() -> MarketIndicatorSnapshotEntity.from(indicators));
        entity.apply(indicators);
        repository.saveAndFlush(entity);
    }
}
