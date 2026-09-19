package com.fixup.analytics.infrastructure;

import com.fixup.analytics.domain.MarketIndicatorSnapshots;
import com.fixup.analytics.domain.MarketIndicators;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cada operación abre y cierra su propia transacción corta.
 *
 * <p>La escritura es atómica y monotónica:
 * <ul>
 *   <li>Dos escrituras iniciales concurrentes no generan colisión de clave primaria.</li>
 *   <li>Un dato con observedAt más antiguo nunca sobreescribe uno más reciente.</li>
 *   <li>cachedAt y source corresponden fielmente al dato aceptado.</li>
 * </ul>
 */
@Repository
class JpaMarketIndicatorSnapshots implements MarketIndicatorSnapshots {

    private static final String POSTGRES_UPSERT = """
            INSERT INTO market_indicator_snapshots (
                zone,
                price_per_square_meter,
                year_over_year_variation_percent,
                average_days_on_market,
                observed_at,
                cached_at,
                source
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (zone) DO UPDATE
            SET price_per_square_meter = EXCLUDED.price_per_square_meter,
                year_over_year_variation_percent = EXCLUDED.year_over_year_variation_percent,
                average_days_on_market = EXCLUDED.average_days_on_market,
                observed_at = EXCLUDED.observed_at,
                cached_at = EXCLUDED.cached_at,
                source = EXCLUDED.source
            WHERE EXCLUDED.observed_at >= market_indicator_snapshots.observed_at
            """;

    private final MarketIndicatorSnapshotJpaRepository repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean isPostgres;

    JpaMarketIndicatorSnapshots(MarketIndicatorSnapshotJpaRepository repository,
            JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
        this.isPostgres = determineIfPostgres(jdbc.getDataSource());
    }

    private static boolean determineIfPostgres(DataSource dataSource) {
        if (dataSource == null) {
            return true;
        }
        try (Connection conn = dataSource.getConnection()) {
            String name = conn.getMetaData().getDatabaseProductName();
            return name != null && name.toLowerCase().contains("postgres");
        } catch (Exception ex) {
            return true;
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<MarketIndicators> findByZone(String zone) {
        return repository.findById(zone).map(MarketIndicatorSnapshotEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveIfNewer(MarketIndicators indicators) {
        Instant now = Instant.now(clock);
        Timestamp observedTs = Timestamp.from(indicators.observedAt());
        Timestamp cachedTs = Timestamp.from(now);

        if (isPostgres) {
            int updated = jdbc.update(POSTGRES_UPSERT,
                    indicators.zone(),
                    indicators.pricePerSquareMeter(),
                    indicators.yearOverYearVariationPercent(),
                    indicators.averageDaysOnMarket(),
                    observedTs,
                    cachedTs,
                    indicators.source().name());
            return updated > 0;
        }

        // Para H2 u otros entornos de prueba en memoria:
        // Intentar primero POSTGRES_UPSERT ya que H2 2.3 corre con MODE=PostgreSQL.
        // Si no soporta EXCLUDED en WHERE o la sintaxis, se aplica la lógica atómica/sincronizada.
        try {
            int updated = jdbc.update(POSTGRES_UPSERT,
                    indicators.zone(),
                    indicators.pricePerSquareMeter(),
                    indicators.yearOverYearVariationPercent(),
                    indicators.averageDaysOnMarket(),
                    observedTs,
                    cachedTs,
                    indicators.source().name());
            return updated > 0;
        } catch (Exception h2SyntaxOrUnsupported) {
            return saveH2Fallback(indicators, now);
        }
    }

    private synchronized boolean saveH2Fallback(MarketIndicators indicators, Instant now) {
        var existing = repository.findById(indicators.zone());
        if (existing.isPresent()) {
            var entity = existing.get();
            if (indicators.observedAt().isBefore(entity.getObservedAt())) {
                return false;
            }
            entity.apply(indicators, now);
            repository.saveAndFlush(entity);
            return true;
        } else {
            var entity = MarketIndicatorSnapshotEntity.from(indicators, now);
            repository.saveAndFlush(entity);
            return true;
        }
    }
}
