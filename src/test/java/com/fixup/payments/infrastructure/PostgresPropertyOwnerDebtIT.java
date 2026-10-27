package com.fixup.payments.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Isolated SQL authorization gate; no Spring context or alternative financial entities. */
@Testcontainers
class PostgresPropertyOwnerDebtIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @BeforeAll
    static void migrate() {
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration", "classpath:db/migration-postgres/postgresql").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(41);
        flyway.validate();
    }

    @Test
    void checksOnlyAuthorizedPropertyOwnersWithoutExposingDebtRows() throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/db/property_owner_debt_authorization.sql"));
             var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            // Each SQL assertion raises on failure; the script checks actual fixup_app RLS and rolls back.
            statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                // Consume all PostgreSQL results so no later assertion failure can be ignored.
            }
        }
    }
}
