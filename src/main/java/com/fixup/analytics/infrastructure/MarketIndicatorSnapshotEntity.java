package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicators;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Owned by analytics. This entity is never shared with another module nor exposed as a contract. */
@Entity
@Table(name = "market_indicator_snapshots")
class MarketIndicatorSnapshotEntity {
    @Id
    @Column(name = "zone", nullable = false, length = 64)
    private String zone;

    @Column(name = "price_per_square_meter", nullable = false, precision = 15, scale = 2)
    private BigDecimal pricePerSquareMeter;

    @Column(name = "year_over_year_variation_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal yearOverYearVariationPercent;

    @Column(name = "average_days_on_market", nullable = false)
    private int averageDaysOnMarket;

    /** Cuándo la fuente externa produjo el dato: es la marca de frescura. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** Cuándo se guardó en esta caché. Sirve para auditar, no para decidir frescura. */
    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    protected MarketIndicatorSnapshotEntity() {
    }

    static MarketIndicatorSnapshotEntity from(MarketIndicators indicators) {
        var entity = new MarketIndicatorSnapshotEntity();
        entity.zone = indicators.zone();
        entity.apply(indicators);
        return entity;
    }

    void apply(MarketIndicators indicators) {
        this.pricePerSquareMeter = indicators.pricePerSquareMeter();
        this.yearOverYearVariationPercent = indicators.yearOverYearVariationPercent();
        this.averageDaysOnMarket = indicators.averageDaysOnMarket();
        this.observedAt = indicators.observedAt();
        this.cachedAt = Instant.now();
    }

    MarketIndicators toDomain() {
        return new MarketIndicators(zone, pricePerSquareMeter, yearOverYearVariationPercent,
                averageDaysOnMarket, observedAt);
    }
}
