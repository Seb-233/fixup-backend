package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicatorSnapshots;
import com.fixup.analytics.domain.MarketIndicators;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class JpaMarketIndicatorSnapshots implements MarketIndicatorSnapshots {
    private final MarketIndicatorSnapshotJpaRepository repository;

    JpaMarketIndicatorSnapshots(MarketIndicatorSnapshotJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MarketIndicators> findByZone(String zone) {
        return repository.findById(zone).map(MarketIndicatorSnapshotEntity::toDomain);
    }

    @Override
    public void save(MarketIndicators indicators) {
        var entity = repository.findById(indicators.zone())
                .orElseGet(() -> MarketIndicatorSnapshotEntity.from(indicators));
        entity.apply(indicators);
        repository.saveAndFlush(entity);
    }
}
