package com.fixup.integration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La suite de la entrega completa contra PostgreSQL real, donde RLS sí existe. Además del contrato
 * HTTP, comprueba las políticas nuevas leyendo las tablas como fixup_app con un actor publicado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
class PostgresEntregaCompletaIT extends EntregaCompletaHttpContract {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Override
    protected org.springframework.test.web.servlet.ResultMatcher expectedHiddenRequestStatus() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound();
    }

    @Test
    void newTablesAreInstalledWithForcedRowLevelSecurity() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version IN ('22','23','24','25','26','27','28','29','30') AND success = true",
                Integer.class)).isEqualTo(9);
        for (String table : List.of("property_photos", "property_work_history", "request_invitations",
                "technical_visits", "visit_checklist_items", "owner_payments", "service_ratings")) {
            assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = ?",
                    Boolean.class, table)).as(table).isTrue();
        }
    }

    @Test
    void tenantSeesTheRentedPropertyOnlyWhileTheLeaseIsActive() throws Exception {
        String owner = "auth0|rls-lease-owner";
        UUID ownerId = provision(owner, "OWNER");
        UUID tenantId = provision("auth0|rls-lease-tenant", "TENANT");
        UUID propertyId = property(owner);
        jdbc.update("UPDATE properties SET status = 'ARCHIVED' WHERE id = ?", propertyId);

        assertThat(visibleIds(tenantId, "TENANT", "SELECT id FROM properties")).doesNotContain(propertyId);
        UUID contractId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rental_contracts (id, property_id, owner_user_id, tenant_user_id, start_date, end_date,
                    monthly_rent_amount, deposit_amount, payment_day_of_month, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_DATE - 30, CURRENT_DATE + 300, 2000000, 2000000, 5, 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, contractId, propertyId, ownerId, tenantId);
        assertThat(visibleIds(tenantId, "TENANT", "SELECT id FROM properties")).contains(propertyId);
        jdbc.update("UPDATE rental_contracts SET status = 'TERMINATED' WHERE id = ?", contractId);
        assertThat(visibleIds(tenantId, "TENANT", "SELECT id FROM properties")).doesNotContain(propertyId);
    }

    @Test
    void chargesAndVisitsAreOnlyVisibleToTheirPartiesWhileReviewsArePublic() throws Exception {
        String owner = "auth0|rls-charge-owner";
        String fixer = "auth0|rls-charge-fixer";
        provision(owner, "OWNER");
        UUID fixerId = verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID strangerOwner = provision("auth0|rls-charge-stranger", "OWNER");
        UUID strangerFixer = verifiedFixer("auth0|rls-charge-stranger-fixer", "Bogotá", "PLUMBING");
        UUID propertyId = property(owner);
        UUID jobId = completedPlumbingJob(owner, fixer, propertyId, 180_000);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ratings").with(as(owner))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":\"%s\",\"score\":4}".formatted(jobId)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        assertThat(visibleIds(fixerId, "FIXER", "SELECT id FROM owner_payments")).hasSize(1);
        assertThat(visibleIds(strangerOwner, "OWNER", "SELECT id FROM owner_payments")).isEmpty();
        assertThat(visibleIds(strangerFixer, "FIXER", "SELECT id FROM owner_payments")).isEmpty();
        assertThat(visibleIds(strangerOwner, "OWNER", "SELECT id FROM service_ratings")).hasSize(1);
        assertThat(visibleIds(strangerFixer, "FIXER", "SELECT id FROM property_work_history")).isEmpty();

        // Another owner's UPDATE matches no visible row, so it changes nothing.
        int updated = actingAs(strangerOwner, "OWNER", connection -> {
            try (var statement = connection.prepareStatement("UPDATE owner_payments SET attempts = 99")) {
                return statement.executeUpdate();
            }
        });
        assertThat(updated).isZero();
        assertThat(jdbc.queryForObject("SELECT max(attempts) FROM owner_payments", Integer.class)).isLessThan(99);
        assertThatThrownBy(() -> actingAs(strangerOwner, "OWNER", connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO service_ratings (id, job_id, request_id, "
                    + "fixer_user_id, rater_user_id, score, created_at) SELECT ?, job_id, request_id, fixer_user_id, ?, 1, "
                    + "CURRENT_TIMESTAMP FROM property_work_history")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, fixerId);
                return statement.executeUpdate();
            }
        })).hasMessageContaining("row-level security");
    }

    private List<UUID> visibleIds(UUID userId, String role, String sql) {
        return actingAs(userId, role, connection -> {
            var ids = new ArrayList<UUID>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    ids.add((UUID) rows.getObject(1));
                }
            }
            return ids;
        });
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private <T> T actingAs(UUID userId, String role, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE fixup_app");
            }
            try (var statement = connection.prepareStatement(
                    "SELECT set_config('app.current_user_id', ?, true), set_config('app.current_user_roles', ?, true)")) {
                statement.setString(1, userId.toString());
                statement.setString(2, role);
                statement.execute();
            }
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
