package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.identityaccess.application.IdentitySession;
import com.fixup.shared.security.DatabaseActorContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
class PostgresIdentityRuntimeIT {
    /** Captures SQL submitted through Hibernate, including native queries, on the HTTP test thread. */
    public static class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<java.util.List<String>> CURRENT = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            var statements = CURRENT.get();
            if (statements != null) statements.add(sql.toLowerCase(java.util.Locale.ROOT));
            return sql;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("db/bg02_runtime.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration", "classpath:db/migration-postgres/postgresql")
                .load().migrate();
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            String fixture = new org.springframework.core.io.ClassPathResource("db/bg02_identity_schema.sql")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            statement.execute(fixture);
        } catch (Exception exception) { throw new IllegalStateException("Cannot initialize isolated identity fixture", exception); }
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", () -> SqlCapture.class.getName());
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "bg02_runtime");
        registry.add("spring.datasource.password", () -> "isolated-test-only");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @org.junit.jupiter.api.BeforeEach
    void restoreConfirmedGreenPrivileges() throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("DROP POLICY IF EXISTS roles_app_self_insert ON user_roles");
            statement.execute("DROP POLICY IF EXISTS roles_app_self_insert_guard ON user_roles");
            statement.execute("DROP POLICY IF EXISTS fixture_overpermissive ON user_roles");
            statement.execute("REVOKE INSERT(user_id, role) ON user_roles FROM fixup_app");
        }
    }

    private void enableRoleSelectionProposal() throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            admin.setAutoCommit(false);
            try {
                statement.execute(java.nio.file.Files.readString(java.nio.file.Path.of(
                        "docs/pending-migrations/BG02/select_role_privileges.sql")));
                admin.commit();
            } catch (Exception failure) { admin.rollback(); throw failure; }
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired IdentitySession identities;
    @Autowired com.fixup.identityaccess.api.CurrentActorProvider actors;
    @Autowired com.fixup.identityaccess.application.UserLookup lookup;
    @Autowired com.fixup.identityaccess.application.UserRegistration registration;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private UUID bootstrap(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap")
                        .header("Authorization", "Bearer " + TestJwtConfiguration.token(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void bootstrapAndMeWorkWithoutRoleSelectionSql() throws Exception {
        for (String path : new String[]{"/auth/bootstrap", "/auth/me"}) {
            var request = path.endsWith("bootstrap") ? post(path) : get(path);
            mvc.perform(request).andExpect(status().isUnauthorized());
            var invalid = path.endsWith("bootstrap") ? post(path) : get(path);
            mvc.perform(invalid.header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("auth0|unknown-runtime"))))
                .andExpect(status().isConflict());
        UUID id = bootstrap("auth0|bootstrap-only");
        mvc.perform(post("/auth/bootstrap").with(jwt().jwt(j -> j.subject("auth0|bootstrap-only"))))
                .andExpect(status().isOk());
        mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("auth0|bootstrap-only"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        try (var c = dataSource.getConnection(); var statement = c.createStatement();
             var rows = statement.executeQuery("SELECT has_any_column_privilege('fixup_app', 'users', 'UPDATE')")) {
            rows.next(); assertThat(rows.getBoolean(1)).isTrue();
        }
        assertThatThrownBy(() -> mvc.perform(post("/auth/select-role")
                .with(jwt().jwt(j -> j.subject("auth0|bootstrap-only")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}")))
                .isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(DatabaseActorContext.current()).isEmpty();
        assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
        assertCleanPool();
    }

    @Test
    void actorlessTransactionDoesNotActivateIdentityAndHttpClearsStaleContext() throws Exception {
        var jdbc = new JdbcTemplate(dataSource);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        String sqlUser = transaction.execute(status -> jdbc.queryForObject("SELECT current_user", String.class));
        assertThat(sqlUser).isEqualTo("bg02_runtime");
        assertThatThrownBy(() -> transaction.execute(status -> jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        DatabaseActorContext.set(new DatabaseActorContext.Actor(UUID.randomUUID(), java.util.Set.of("PLATFORM_ADMIN")));
        com.fixup.shared.security.DatabaseIdentityContext.withSubject("auth0|stale-thread", () -> {
            try {
                mvc.perform(get("/auth/me").header("Authorization", "Bearer invalid"))
                        .andExpect(status().isUnauthorized());
                assertThat(DatabaseActorContext.current()).isEmpty();
                assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
                return null;
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
        assertCleanPool();
    }

    @Test
    void identityScopeRequiresAuthenticationAndRestoresOuterActorTransaction() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> identities.read("auth0|background", () -> "unreachable"))
                .isInstanceOf(org.springframework.security.authentication.InsufficientAuthenticationException.class);
        assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();

        UUID id = bootstrap("auth0|nested-identity");
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                .header("alg", "RS256").subject("auth0|nested-identity").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, java.util.List.of()));
        var actor = new DatabaseActorContext.Actor(id, java.util.Set.of());
        DatabaseActorContext.set(actor);
        var jdbc = new JdbcTemplate(dataSource);
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("fixup_app");
                String innerRole = identities.read("auth0|nested-identity", () -> {
                    assertThat(DatabaseActorContext.current()).isEmpty();
                    return jdbc.queryForObject("SELECT current_user", String.class);
                });
                assertThat(innerRole).isEqualTo("fixup_identity");
                assertThat(DatabaseActorContext.current()).contains(actor);
                assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("fixup_app");
            });
        } finally {
            DatabaseActorContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertCleanPool();
    }

    @Test
    void fullAuthenticationWithNoInheritRuntime() throws Exception {
        UUID alice = bootstrap("auth0|runtime-alice");
        UUID bob = bootstrap("auth0|runtime-bob");
        mvc.perform(post("/auth/bootstrap").with(jwt().jwt(j -> j.subject("auth0|runtime-alice"))))
                .andExpect(status().isOk());
        enableRoleSelectionProposal();
        for (String role : new String[]{"OWNER", "TENANT", "FIXER"}) {
            mvc.perform(post("/auth/select-role").with(jwt().jwt(j -> j.subject("auth0|runtime-alice")))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.roles").isArray());
        }
        mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("auth0|runtime-alice"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(alice.toString()));
        mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("auth0|runtime-bob"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(bob.toString()))
                .andExpect(jsonPath("$.roles").isEmpty());
        for (String role : new String[]{"PLATFORM_ADMIN", "REAL_ESTATE_MANAGER"}) {
            mvc.perform(post("/auth/select-role").with(jwt().jwt(j -> j.subject("auth0|runtime-bob")))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/auth/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var calls = java.util.List.<java.util.concurrent.Callable<Void>>of(
                    () -> { mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("auth0|runtime-alice"))))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(alice.toString())); return null; },
                    () -> { mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("auth0|runtime-bob"))))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.roles").isEmpty()); return null; });
            for (var future : executor.invokeAll(calls)) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        String signed = TestJwtConfiguration.token("auth0|runtime-alice");
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + signed)).andExpect(status().isOk());
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var s = admin.prepareStatement("UPDATE users SET status = 'SUSPENDED' WHERE id = ?")) {
                s.setObject(1, alice); s.executeUpdate();
            }
        }
        for (String path : new String[]{"/auth/bootstrap", "/auth/me", "/auth/select-role"}) {
            var request = path.equals("/auth/me") ? get(path) : post(path);
            mvc.perform(request.with(jwt().jwt(j -> j.subject("auth0|runtime-alice")))
                    .contentType(MediaType.APPLICATION_JSON).content(path.equals("/auth/select-role") ? "{\"role\":\"OWNER\"}" : "{}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(DatabaseActorContext.current()).isEmpty();
        assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
        assertCleanPool();
    }

    @Test
    void identityTransactionRollsBackAndRejectsAnotherSubject() throws Exception {
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                .header("alg", "RS256").subject("auth0|rollback").build();
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, java.util.List.of()));
        try {
            assertThatThrownBy(() -> lookup.bySubject("auth0|victim"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> registration.register(new com.fixup.identityaccess.domain.ExternalIdentity(
                    "auth0|victim", "synthetic@example.test", "Synthetic account")))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> identities.execute("auth0|victim", () -> {
                throw new AssertionError("Must reject before executing work");
            })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> identities.execute("auth0|rollback", () -> {
                new JdbcTemplate(dataSource).update("INSERT INTO users(id, auth0_subject, status, created_at, updated_at) "
                        + "VALUES (?, 'auth0|rollback', 'ACTIVE', now(), now())", UUID.randomUUID());
                throw new IllegalStateException("force rollback after insert");
            })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback after insert");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
        assertCleanPool();
        bootstrap("auth0|rollback"); // 201 proves the previous insert did not survive rollback.
    }

    @Test
    void roleAndFixerProfileRollBackTogether() throws Exception {
        UUID id = bootstrap("auth0|profile-rollback");
        enableRoleSelectionProposal();
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            java.sql.Timestamp originalUpdated;
            try (var r = statement.executeQuery("SELECT updated_at FROM users WHERE id = '" + id + "'")) {
                r.next(); originalUpdated = r.getTimestamp(1);
            }
            statement.execute("ALTER TABLE fixer_profiles ADD CONSTRAINT bg02_test_reject_profile CHECK (user_id <> '" + id + "')");
            try {
                // The database rejects the synchronous FIXER listener, after the role INSERT.
                assertThatThrownBy(() -> mvc.perform(post("/auth/select-role")
                        .with(jwt().jwt(j -> j.subject("auth0|profile-rollback")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}")))
                        .isInstanceOf(jakarta.servlet.ServletException.class)
                        .hasRootCauseInstanceOf(java.sql.SQLException.class);
            } finally {
                statement.execute("ALTER TABLE fixer_profiles DROP CONSTRAINT bg02_test_reject_profile");
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM user_roles WHERE user_id = '" + id + "'")) {
                result.next(); assertThat(result.getInt(1)).isZero();
            }
            try (var result = statement.executeQuery("SELECT count(*) FROM fixer_profiles WHERE user_id = '" + id + "'")) {
                result.next(); assertThat(result.getInt(1)).isZero();
            }
            try (var result = statement.executeQuery("SELECT updated_at FROM users WHERE id = '" + id + "'")) {
                result.next(); assertThat(result.getTimestamp(1)).isEqualTo(originalUpdated);
            }
        }
        mvc.perform(post("/auth/select-role").with(jwt().jwt(j -> j.subject("auth0|profile-rollback")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                .andExpect(status().isOk());
        assertCleanPool();
    }

    @Test
    void failedActorActivationReleasesTransactionResources() throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("REVOKE fixup_app FROM bg02_runtime");
            DatabaseActorContext.set(new DatabaseActorContext.Actor(UUID.randomUUID(), java.util.Set.of()));
            try {
                assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                        .execute(status -> "unreachable")).isInstanceOf(RuntimeException.class);
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                        .hasResource(dataSource)).isFalse();
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                        .hasResource(entityManagerFactory)).isFalse();
            } finally {
                DatabaseActorContext.clear();
                statement.execute("GRANT fixup_app TO bg02_runtime WITH INHERIT FALSE, SET TRUE");
            }
        }
        assertCleanPool();
        bootstrap("auth0|after-activation-failure");
    }

    @Test
    void actorRevalidationInsideConcurrentTransactionsNeedsNoExtraConnection() throws Exception {
        UUID id = bootstrap("auth0|pool-actor");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Void> work = () -> {
            var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                    .header("alg", "RS256").subject("auth0|pool-actor").build();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, java.util.List.of()));
            DatabaseActorContext.set(new DatabaseActorContext.Actor(id, java.util.Set.of()));
            try {
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    try { barrier.await(15, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                    assertThat(actors.currentActor().internalUserId()).isEqualTo(id);
                });
            } finally {
                DatabaseActorContext.clear();
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
            return null;
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (var future : executor.invokeAll(java.util.List.of(work, work)))
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertCleanPool();
    }

    private org.springframework.test.web.servlet.ResultActions selectRole(String subject, String role) throws Exception {
        return mvc.perform(post("/auth/select-role")
                .header("Authorization", "Bearer " + TestJwtConfiguration.token(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"OWNER,TENANT", "TENANT,FIXER", "FIXER,OWNER"})
    void firstConsecutiveAndRepeatedRolesUseOnlyNarrowSql(String first, String second) throws Exception {
        String subject = "auth0|sequence-" + first;
        UUID id = bootstrap(subject);
        enableRoleSelectionProposal();
        var sql = new java.util.ArrayList<String>();
        SqlCapture.CURRENT.set(sql);
        try {
            selectRole(subject, first).andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.contains(first)));
            selectRole(subject, second).andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder(first, second)));
            selectRole(subject, second).andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder(first, second)));
        } finally {
            SqlCapture.CURRENT.remove();
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/bg02-consecutive-sql.txt"),
                    first + " -> " + second + " -> " + second + "\n" + String.join("\n", sql) + "\n\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        assertThat(sql.stream().filter(q -> q.startsWith("insert into user_roles"))).hasSize(2);
        assertThat(sql.stream().filter(q -> q.startsWith("update users set updated_at"))).hasSize(2);
        assertThat(sql.stream().filter(q -> q.matches(".*for (no key )?update.*"))).hasSize(2);
        assertThat(sql).noneMatch(q -> q.startsWith("delete from user_roles") || q.startsWith("update user_roles")
                || (q.startsWith("update users") && !q.startsWith("update users set updated_at")));
        try (var admin = adminConnection(); var s = admin.createStatement()) {
            try (var r = s.executeQuery("SELECT count(*) FROM user_roles WHERE user_id = '" + id + "'")) {
                r.next(); assertThat(r.getInt(1)).isEqualTo(2);
            }
            if (first.equals("FIXER") || second.equals("FIXER")) {
                try (var r = s.executeQuery("SELECT count(*) FROM fixer_profiles WHERE user_id = '" + id + "'")) {
                    r.next(); assertThat(r.getInt(1)).isEqualTo(1);
                }
            }
        }
        assertCleanPool();
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"foreign", "admin", "manager", "suspended", "actorless", "update", "delete"})
    void databaseRejectsUnsafeWritesEvenWithAnotherPermissivePolicy(String attempt) throws Exception {
        UUID self = bootstrap("auth0|guard-self-" + attempt);
        UUID other = bootstrap("auth0|guard-other-" + attempt);
        enableRoleSelectionProposal();
        try (var admin = adminConnection(); var s = admin.createStatement()) {
            s.execute("CREATE POLICY fixture_overpermissive ON user_roles FOR INSERT TO PUBLIC WITH CHECK (true)");
            if (attempt.equals("suspended")) s.execute("UPDATE users SET status = 'SUSPENDED' WHERE id = '" + self + "'");
        }
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("SET LOCAL ROLE fixup_app");
                if (!attempt.equals("actorless")) {
                    try (var actor = c.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                        actor.setString(1, self.toString()); actor.execute();
                    }
                }
                String role = attempt.equals("admin") ? "PLATFORM_ADMIN" : attempt.equals("manager") ? "REAL_ESTATE_MANAGER" : "OWNER";
                UUID target = attempt.equals("foreign") ? other : self;
                if (attempt.equals("update") || attempt.equals("delete")) {
                    s.execute("INSERT INTO user_roles(user_id, role) VALUES ('" + self + "', 'OWNER')");
                }
                String sql = switch (attempt) {
                    case "update" -> "UPDATE user_roles SET role = 'TENANT' WHERE user_id = '" + self + "'";
                    case "delete" -> "DELETE FROM user_roles WHERE user_id = '" + self + "'";
                    default -> "INSERT INTO user_roles(user_id, role) VALUES ('" + target + "', '" + role + "')";
                };
                assertThatThrownBy(() -> s.execute(sql)).isInstanceOfSatisfying(java.sql.SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            } finally { c.rollback(); }
        }
        assertCleanPool();
    }

    @Test
    void proposalPreservesWriterAndExactPrivileges() throws Exception {
        String writerBefore;
        try (var c = adminConnection(); var s = c.createStatement(); var r = s.executeQuery(
                "SELECT row_to_json(p)::text FROM pg_policies p WHERE schemaname = 'public' AND policyname = 'roles_writer'")) {
            r.next(); writerBefore = r.getString(1);
        }
        enableRoleSelectionProposal();
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT row_to_json(p)::text FROM pg_policies p WHERE schemaname = 'public' AND policyname = 'roles_writer'")) {
                r.next(); assertThat(r.getString(1)).isEqualTo(writerBefore);
            }
            try (var r = s.executeQuery("SELECT c.relname, a.attname, "
                    + "has_column_privilege('fixup_app', c.oid, a.attnum, 'SELECT'), "
                    + "has_column_privilege('fixup_app', c.oid, a.attnum, 'INSERT'), "
                    + "has_column_privilege('fixup_app', c.oid, a.attnum, 'UPDATE') "
                    + "FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid "
                    + "WHERE c.oid IN ('users'::regclass, 'user_roles'::regclass) AND a.attnum > 0 AND NOT a.attisdropped")) {
                int columns = 0;
                while (r.next()) {
                    columns++;
                    boolean roles = r.getString(1).equals("user_roles");
                    assertThat(r.getBoolean(3)).isTrue();
                    assertThat(r.getBoolean(4)).isEqualTo(roles && java.util.Set.of("user_id", "role").contains(r.getString(2)));
                    assertThat(r.getBoolean(5)).isEqualTo(!roles && r.getString(2).equals("updated_at"));
                }
                assertThat(columns).isGreaterThan(2);
            }
            for (String table : new String[]{"users", "user_roles"}) {
                for (String privilege : new String[]{"INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                    try (var r = s.executeQuery("SELECT has_table_privilege('fixup_app', '" + table + "', '" + privilege + "')")) {
                        r.next(); assertThat(r.getBoolean(1)).isFalse();
                    }
                }
                for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                    try (var r = s.executeQuery("SELECT has_table_privilege('bg02_runtime', '" + table + "', '" + privilege + "')")) {
                        r.next(); assertThat(r.getBoolean(1)).isFalse();
                    }
                }
            }
            try (var r = s.executeQuery("SELECT count(*) FROM pg_class WHERE oid IN ('users'::regclass, 'user_roles'::regclass) AND relrowsecurity AND relforcerowsecurity")) {
                r.next(); assertThat(r.getInt(1)).isEqualTo(2);
            }
            try (var r = s.executeQuery("SELECT pg_has_role('bg02_runtime', 'fixup_identity_writer', 'MEMBER'), rolinherit, rolbypassrls, rolsuper FROM pg_roles WHERE rolname = 'bg02_runtime'")) {
                r.next(); for (int i = 1; i <= 4; i++) assertThat(r.getBoolean(i)).isFalse();
            }
        }
        assertCleanPool();
    }

    @Test
    void nestedIdentityCannotSwitchSubjectAndRestoresAfterInnerRollback() throws Exception {
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "RS256").subject("auth0|outer").build();
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var original = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, java.util.List.of());
        security.setAuthentication(original);
        try {
            identities.read("auth0|outer", () -> {
                var jdbc = new JdbcTemplate(dataSource);
                assertThat(jdbc.queryForObject("SELECT current_setting('app.identity_subject')", String.class))
                        .isNotNull();
                var changed = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("changed").header("alg", "RS256").subject("auth0|other").build();
                security.setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(changed, java.util.List.of()));
                try {
                    assertThatThrownBy(() -> identities.read("auth0|other", () -> "unreachable"))
                            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                } finally { security.setAuthentication(original); }
                assertThatThrownBy(() -> identities.read("auth0|outer", () -> {
                    throw new IllegalStateException("inner rollback");
                })).isInstanceOf(IllegalStateException.class).hasMessage("inner rollback");
                assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).contains("auth0|outer");
                assertThat(jdbc.queryForObject("SELECT current_setting('app.identity_subject')", String.class)).isEqualTo("auth0|outer");
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("fixup_identity");
                return null;
            });
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
        assertCleanPool();
    }

    @Test
    void failedIdentityActivationReleasesResourcesAndContext() throws Exception {
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "RS256").subject("auth0|identity-failure").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, java.util.List.of()));
        try (var c = adminConnection(); var s = c.createStatement()) {
            s.execute("REVOKE fixup_identity FROM bg02_runtime");
            try {
                assertThatThrownBy(() -> identities.read("auth0|identity-failure", () -> "unreachable")).isInstanceOf(RuntimeException.class);
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
                assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
            } finally {
                s.execute("GRANT fixup_identity TO bg02_runtime WITH INHERIT FALSE, SET TRUE");
            }
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        assertCleanPool();
        bootstrap("auth0|after-identity-failure");
    }

    @Test
    void httpCannotChooseIdentityEvenThoughIdentityReadPoliciesAreTrue() throws Exception {
        UUID self = bootstrap("auth0|http-self");
        bootstrap("auth0|http-other");
        mvc.perform(post("/auth/bootstrap").header("Authorization", "Bearer " + TestJwtConfiguration.token("auth0|http-self"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"auth0|http-other\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + TestJwtConfiguration.token("auth0|http-self"))
                .header("X-Identity-Subject", "auth0|http-other").param("subject", "auth0|http-other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(self.toString()));
        var unauthorizedSql = new java.util.ArrayList<String>();
        SqlCapture.CURRENT.set(unauthorizedSql);
        try {
            for (String path : new String[]{"/auth/bootstrap", "/auth/me", "/auth/select-role"}) {
                var request = path.equals("/auth/me") ? get(path) : post(path);
                mvc.perform(request.header("X-Identity-Subject", "auth0|http-other"))
                        .andExpect(status().isUnauthorized());
                var invalid = path.equals("/auth/me") ? get(path) : post(path);
                mvc.perform(invalid.header("Authorization", "Bearer invalid"))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(post("/arbitrary-identity").header("Authorization", "Bearer " + TestJwtConfiguration.token("auth0|http-self")))
                    .andExpect(status().isForbidden());
        } finally { SqlCapture.CURRENT.remove(); }
        assertThat(unauthorizedSql).isEmpty();
        assertThat(DatabaseActorContext.current()).isEmpty();
        assertThat(com.fixup.shared.security.DatabaseIdentityContext.currentSubject()).isEmpty();
        assertCleanPool();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void failedBeginWithRollbackAndCleanupFaultsCannotContaminatePool(boolean rollbackFails, boolean cleanupFails) throws Exception {
        var rollbackFault = new java.util.concurrent.atomic.AtomicBoolean(rollbackFails);
        var cleanupFault = new java.util.concurrent.atomic.AtomicBoolean(cleanupFails);
        var emf = (jakarta.persistence.EntityManagerFactory) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{jakarta.persistence.EntityManagerFactory.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    Object value = invoke(method, entityManagerFactory, args);
                    if (!(value instanceof jakarta.persistence.EntityManager real)) return value;
                    var realTx = real.getTransaction();
                    var tx = (jakarta.persistence.EntityTransaction) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[]{jakarta.persistence.EntityTransaction.class}, (p, m, a) -> {
                                if (m.getName().equals("rollback") && rollbackFault.getAndSet(false))
                                    throw new jakarta.persistence.PersistenceException("injected rollback before physical rollback");
                                return invoke(m, realTx, a);
                            });
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{jakarta.persistence.EntityManager.class}, (p, m, a) ->
                                    m.getName().equals("getTransaction") ? tx : invoke(m, real, a));
                });
        var constructor = Class.forName("com.fixup.shared.configuration.RlsSessionTransactionManager")
                .getDeclaredConstructor(jakarta.persistence.EntityManagerFactory.class);
        constructor.setAccessible(true);
        var manager = (org.springframework.orm.jpa.JpaTransactionManager) constructor.newInstance(emf);
        manager.setDataSource(dataSource);
        var originalDialect = ((org.springframework.orm.jpa.JpaTransactionManager) transactionManager).getJpaDialect();
        var delegate = (org.springframework.orm.jpa.JpaDialect) org.springframework.test.util.ReflectionTestUtils
                .getField(originalDialect, "delegate");
        assertThat(delegate).isNotNull();
        var faultDialect = (org.springframework.orm.jpa.JpaDialect) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.springframework.orm.jpa.JpaDialect.class}, (p, m, a) -> {
                    if (m.getName().equals("cleanupTransaction") && cleanupFault.getAndSet(false))
                        throw new IllegalStateException("injected dialect cleanup");
                    return invoke(m, delegate, a);
                });
        manager.setJpaDialect(faultDialect);
        var template = new org.springframework.transaction.support.TransactionTemplate(manager);
        DatabaseActorContext.set(new DatabaseActorContext.Actor(UUID.randomUUID(), java.util.Set.of()));
        try {
            try (var admin = adminConnection(); var s = admin.createStatement()) {
                s.execute("REVOKE fixup_app FROM bg02_runtime");
                try {
                    var failure = catchThrowable(() -> template.execute(status -> {
                        throw new AssertionError("failed activation must not run work");
                    }));
                    assertThat(failure).hasRootCauseInstanceOf(java.sql.SQLException.class);
                    Throwable root = failure;
                    while (root.getCause() != null) root = root.getCause();
                    assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("42501");
                    assertThat(root.getMessage()).contains("permission denied to set role");
                    var messages = new java.util.ArrayList<String>();
                    collectFailures(failure, messages);
                    if (rollbackFails) assertThat(messages).anyMatch(m -> m.contains("injected rollback"));
                    if (cleanupFails) assertThat(messages).anyMatch(m -> m.contains("injected dialect cleanup"));
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()).isEmpty();
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                } finally { s.execute("GRANT fixup_app TO bg02_runtime WITH INHERIT FALSE, SET TRUE"); }
            }
            // Inspect all pool members after failure BEFORE allowing another transaction.
            assertCleanPool();
            String role = template.execute(status -> new JdbcTemplate(dataSource).queryForObject("SELECT current_user", String.class));
            assertThat(role).isEqualTo("fixup_app");
        } finally { DatabaseActorContext.clear(); }
        assertCleanPool();
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static void collectFailures(Throwable failure, java.util.List<String> messages) {
        messages.add(String.valueOf(failure.getMessage()));
        if (failure.getCause() != null) collectFailures(failure.getCause(), messages);
        for (var suppressed : failure.getSuppressed()) collectFailures(suppressed, messages);
    }

    private void assertCleanPool() throws Exception {
        // Hold both connections simultaneously, so both physical pool members are inspected.
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            for (Connection c : new Connection[]{first, second}) {
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT current_user, "
                        + "nullif(current_setting('app.identity_subject', true), ''), "
                        + "nullif(current_setting('app.current_user_id', true), ''), "
                        + "nullif(current_setting('app.current_user_roles', true), '')")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo("bg02_runtime");
                    for (int i = 2; i <= 4; i++) assertThat(r.getString(i)).isNull();
                }
            }
        }
    }

    @Test
    void databaseGuardsAndRollback() throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            String preflight = java.nio.file.Files.readString(java.nio.file.Path.of("ops/blue-green/bg02-identity-preflight.sql"))
                    .replace("fixup_green_runtime", "bg02_runtime");
            statement.execute(preflight);
        }
        enableRoleSelectionProposal();
        UUID user = bootstrap("auth0|sql-self");
        UUID other = bootstrap("auth0|sql-other");
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                var attributes = s.executeQuery("SELECT rolinherit, rolbypassrls, rolsuper FROM pg_roles WHERE rolname = current_user");
                assertThat(attributes.next()).isTrue();
                for (int i = 1; i <= 3; i++) assertThat(attributes.getBoolean(i)).isFalse();
                var tables = s.executeQuery("SELECT count(*) FROM pg_class WHERE oid IN ('users'::regclass, 'user_roles'::regclass) AND relrowsecurity AND relforcerowsecurity");
                tables.next(); assertThat(tables.getInt(1)).isEqualTo(2);
                assertThatThrownBy(() -> s.execute("SELECT id FROM users"))
                        .isInstanceOfSatisfying(java.sql.SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
                c.rollback();
                s.execute("SET LOCAL ROLE fixup_identity");
                s.execute("SELECT set_config('app.identity_subject', 'auth0|sql-self', true)");
                var rows = s.executeQuery("SELECT count(*) FROM users");
                assertThat(rows.next()).isTrue();
                // Green USING(true) intentionally exposes the directory to this trusted role.
                assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(2);
                assertThatThrownBy(() -> s.execute("INSERT INTO user_roles VALUES ('" + other + "', 'PLATFORM_ADMIN')"))
                        .isInstanceOf(java.sql.SQLException.class);
            } finally { c.rollback(); }
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("SET LOCAL ROLE fixup_app");
                s.execute("SELECT set_config('app.current_user_id', '" + user + "', true), set_config('app.current_user_roles', '', true)");
                try (var rows = s.executeQuery("SELECT count(*) FROM users WHERE id = '" + other + "'")) {
                    rows.next(); assertThat(rows.getInt(1)).isZero();
                }
                assertThatThrownBy(() -> s.execute("INSERT INTO user_roles VALUES ('" + user + "', 'PLATFORM_ADMIN')"))
                        .isInstanceOfSatisfying(java.sql.SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            } finally { c.rollback(); }
        }
        assertCleanPool();
    }
}
