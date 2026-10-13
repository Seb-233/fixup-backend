package com.fixup.integration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
class PostgresRemodelingIT extends RemodelingHttpContract {
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
    protected org.springframework.test.web.servlet.ResultMatcher expectedHiddenProjectStatus() {
        return status().isNotFound();
    }

    @Test
    void migrationsConstraintsIndexesAndForcedRlsAreInstalled() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version IN ('20', '21') AND success = true", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity "
                + "FROM pg_class WHERE relname = 'remodeling_projects'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity "
                + "FROM pg_class WHERE relname = 'remodeling_requirements'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename IN "
                + "('remodeling_projects','remodeling_requirements')", Integer.class)).isGreaterThanOrEqualTo(7);
    }

    @Test
    void rlsShowsProjectOnlyToOwnerAndPlatformAdmin() throws Exception {
        String ownerSubject = "auth0|remodel-rls-owner";
        UUID owner = provisionOwner(ownerSubject);
        UUID projectId = UUID.fromString(createProject(ownerSubject, property(owner)).get("id").asText());
        UUID otherOwner = provisionOwner("auth0|remodel-rls-other");
        UUID fixer = provisionVerifiedFixer("auth0|remodel-rls-fixer", "PLUMBING");
        UUID admin = provisionAdmin("auth0|remodel-rls-admin");

        assertThat(visibleProjectIds(owner, "OWNER")).containsExactly(projectId);
        assertThat(visibleProjectIds(otherOwner, "OWNER")).isEmpty();
        assertThat(visibleProjectIds(fixer, "FIXER")).isEmpty();
        assertThat(visibleProjectIds(admin, "PLATFORM_ADMIN")).containsExactly(projectId);
    }

    @Test
    void ownerCannotInsertAProjectForAnotherOwner() throws Exception {
        UUID owner = provisionOwner("auth0|remodel-rls-insert-owner");
        UUID other = provisionOwner("auth0|remodel-rls-insert-other");
        UUID otherProperty = property(other);

        assertThatThrownBy(() -> actingAs(owner, "OWNER", connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO remodeling_projects "
                    + "(id, property_id, owner_user_id, title, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'Forbidden', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, otherProperty);
                statement.setObject(3, other);
                return statement.executeUpdate();
            }
        })).hasMessageContaining("row-level security policy");
    }

    @Test
    void acceptsQuotationsForDifferentRequirementsConcurrently() throws Exception {
        String ownerSubject = "auth0|remodel-concurrent-owner";
        UUID owner = provisionOwner(ownerSubject);
        var project = createProject(ownerSubject, property(owner));
        UUID projectId = UUID.fromString(project.get("id").asText());
        UUID firstRequest = UUID.fromString(project.at("/requirements/0/requestId").asText());
        UUID secondRequest = UUID.fromString(project.at("/requirements/1/requestId").asText());
        String firstFixer = "auth0|remodel-concurrent-fixer-a";
        String secondFixer = "auth0|remodel-concurrent-fixer-b";
        provisionVerifiedFixer(firstFixer, "PLUMBING");
        provisionVerifiedFixer(secondFixer, "ELECTRICAL");
        UUID firstQuotation = submitQuotation(firstFixer, firstRequest, 100_000);
        UUID secondQuotation = submitQuotation(secondFixer, secondRequest, 200_000);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/quotations/" + firstQuotation + "/accept")
                        .with(identity(ownerSubject))).andReturn().getResponse().getStatus();
            };
            Callable<Integer> second = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/quotations/" + secondQuotation + "/accept")
                        .with(identity(ownerSubject))).andReturn().getResponse().getStatus();
            };
            var futures = List.of(executor.submit(first), executor.submit(second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(futures.get(0).get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(futures.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }

        mvc.perform(get("/remodelings/" + projectId).with(identity(ownerSubject)))
                .andExpect(status().isOk());
        assertProjectStatus(ownerSubject, projectId, "FULLY_ASSIGNED", 2, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE request_id IN (?, ?)",
                Integer.class, firstRequest, secondRequest)).isEqualTo(2);
    }

    private List<UUID> visibleProjectIds(UUID userId, String role) {
        return actingAs(userId, role, connection -> {
            var result = new java.util.ArrayList<UUID>();
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT id FROM remodeling_projects")) {
                while (rows.next()) {
                    result.add((UUID) rows.getObject(1));
                }
            }
            return result;
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
                    "SELECT set_config('app.current_user_id', ?, true), "
                            + "set_config('app.current_user_roles', ?, true)")) {
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
