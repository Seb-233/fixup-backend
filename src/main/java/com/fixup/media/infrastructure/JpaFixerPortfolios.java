package com.fixup.media.infrastructure;

import com.fixup.media.domain.FixerPortfolio;
import com.fixup.media.domain.FixerPortfolios;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JpaFixerPortfolios implements FixerPortfolios {
    private static final String POSTGRES_INSERT = """
            INSERT INTO fixer_portfolios (
                fixer_user_id,
                status,
                published_at,
                updated_at
            )
            VALUES (
                ?,
                'DRAFT',
                NULL,
                ?
            )
            ON CONFLICT (fixer_user_id) DO NOTHING
            """;

    private static final String H2_INSERT = """
            MERGE INTO fixer_portfolios (
                fixer_user_id,
                status,
                published_at,
                updated_at
            )
            KEY (fixer_user_id)
            VALUES (
                ?,
                'DRAFT',
                NULL,
                ?
            )
            """;

    private final FixerPortfolioJpaRepository repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean isPostgres;

    JpaFixerPortfolios(FixerPortfolioJpaRepository repository, JdbcTemplate jdbc, Clock clock) {
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
    public void save(FixerPortfolio portfolio) {
        var entity = repository.findById(portfolio.fixerUserId())
                .orElseGet(() -> FixerPortfolioEntity.from(portfolio));
        entity.apply(portfolio);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<FixerPortfolio> findById(UUID fixerUserId) {
        return repository.findById(fixerUserId).map(FixerPortfolioEntity::toDomain);
    }

    @Override
    public FixerPortfolio findOrCreateForUpdate(UUID fixerUserId) {
        Instant now = Instant.now(clock);
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        if (isPostgres) {
            jdbc.update(POSTGRES_INSERT, fixerUserId, nowTs);
        } else {
            jdbc.update(H2_INSERT, fixerUserId, nowTs);
        }

        return repository.lockByFixerUserId(fixerUserId)
                .map(FixerPortfolioEntity::toDomain)
                .orElseThrow(() -> new IllegalStateException("Failed to find or create portfolio for fixer: " + fixerUserId));
    }
}
