package com.fixup.contracts;

import com.fixup.integration.TestJwtConfiguration;
import com.fixup.shared.security.DatabaseActorContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL gate: fails on missing Docker and never falls back to H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, RentalContractIntegrationTest.ContractTestConfig.class})
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class PostgresRentalContractIT extends RentalContractHttpContract {
    // Active manifest: 16 historical migrations through V16, then these 25 successors.
    // V22/V23 are not active versions; owner_payments remains archived outside Flyway locations.
    private static final List<String> THROUGH_V16 = List.of("1", "2", "3", "4", "5", "6", "7", "8",
            "9", "10", "11", "12", "13", "14", "15", "16");
    private static final List<String> AFTER_V16 = List.of("16.1", "17", "18", "19", "20", "21",
            "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42");
    private static final List<String> ACTIVE_VERSIONS = java.util.stream.Stream
            .concat(THROUGH_V16.stream(), AFTER_V16.stream()).toList();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void upgradesExistingVersion16WithoutRewritingAppliedMigrations() {
        String schema = "public";
        String[] locations = {"classpath:db/migration", "classpath:db/migration-postgres/postgresql"};
        try (var isolated = new PostgreSQLContainer<>("postgres:17-alpine")) {
            isolated.start();
            var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword());
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).schemas(schema).locations(locations).target("16").load().migrate();
            var applied = jdbc.queryForList("SELECT version FROM " + schema + ".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class);
            assertThat(applied).containsExactlyElementsOf(THROUGH_V16);
            var historicalChecksums = jdbc.queryForList("SELECT version, script, checksum FROM " + schema
                    + ".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank");
            var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations(locations).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(AFTER_V16.size());
            flyway.validate();
            assertThat(jdbc.queryForList("SELECT version FROM " + schema + ".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactlyElementsOf(ACTIVE_VERSIONS);
            assertThat(jdbc.queryForList("SELECT version, script, checksum FROM " + schema
                    + ".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank").subList(0, THROUGH_V16.size()))
                    .containsExactlyElementsOf(historicalChecksums);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = ? AND table_name = 'owner_payments'", Integer.class, schema)).isZero();
        }
    }

    @Test
    void postgresEnforcesContractDateAndRentConstraints() throws Exception {
        var fixture = createFixture(LocalDate.now().plusMonths(6));
        assertThatThrownBy(() -> jdbc.update("UPDATE rental_contracts SET end_date = start_date WHERE id = ?", fixture.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_contract_dates");
        assertThatThrownBy(() -> jdbc.update("UPDATE rental_contracts SET monthly_rent_amount = 0 WHERE id = ?", fixture.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_contract_rent");
        assertThat(jdbc.queryForObject("SELECT monthly_rent_amount FROM rental_contracts WHERE id = ?", Long.class, fixture.id()))
                .isEqualTo(1_000_000L);
    }

    @Test
    void usesPostgresWithValidatedMigrationsAndExistingRlsModel() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class)).containsExactlyElementsOf(ACTIVE_VERSIONS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name = 'rental_contracts' AND column_name = 'end_date'", String.class))
                .isEqualTo("date");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name = 'rental_contracts' AND column_name = 'monthly_rent_amount'", String.class))
                .isEqualTo("bigint");
        assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'properties'::regclass", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid = 'rental_contracts'::regclass", Boolean.class))
                .isFalse(); // Contracts currently relies on application authorization; do not invent a policy.

        DatabaseActorContext.set(new DatabaseActorContext.Actor(UUID.randomUUID(), Set.of("OWNER")));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("fixup_app");
                assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                        .isFalse();
            });
        } finally {
            DatabaseActorContext.clear();
        }
    }
}
