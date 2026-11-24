package com.fixup.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PostgresRoleInheritanceIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("db/bg02_role_review.sql");
    private static final String SELF = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";

    @BeforeAll static void proposal() throws Exception {
        try (var c = admin(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute(Files.readString(Path.of("docs/pending-migrations/BG02/select_role_privileges.sql")));
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixup_green", "bg02_super_member", "bg02_bypass_member"})
    void authorizedBypassAdministrationStillWorksWithForceRls(String role) throws Exception {
        // fixup_green uses its own LOGIN; extra roles independently exercise SUPERUSER vs BYPASSRLS.
        try (var c = role.equals("fixup_green")
                ? DriverManager.getConnection(POSTGRES.getJdbcUrl(), "fixup_green", "isolated-owner-test") : admin();
             var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("SET LOCAL ROLE " + role);
                // The inherited column INSERT ACL is still required; bypass only skips RLS.
                s.execute("INSERT INTO user_roles VALUES ('" + OTHER + "', 'PLATFORM_ADMIN')");
                s.execute("INSERT INTO user_roles VALUES ('" + SELF + "', 'REAL_ESTATE_MANAGER')");
                try (var r = s.executeQuery("SELECT count(*) FROM pg_class WHERE oid IN ('users'::regclass, 'user_roles'::regclass) AND relrowsecurity AND relforcerowsecurity")) {
                    r.next(); assertThat(r.getInt(1)).isEqualTo(2);
                }
            } finally { c.rollback(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLATFORM_ADMIN", "REAL_ESTATE_MANAGER", "foreign"})
    void ownerSessionCannotBypassSelfAssignmentAfterSetRoleApp(String attempt) throws Exception {
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "fixup_green", "isolated-owner-test");
             var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("SET LOCAL ROLE fixup_app");
                s.execute("SELECT set_config('app.current_user_id', '" + SELF + "', true)");
                String id = attempt.equals("foreign") ? OTHER : SELF;
                String role = attempt.equals("foreign") ? "OWNER" : attempt;
                assertThatThrownBy(() -> s.execute("INSERT INTO user_roles VALUES ('" + id + "', '" + role + "')"))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            } finally { c.rollback(); }
        }
    }

    @Test
    void futureNonBypassOwnerIsStillRestrictedAndGuardRejectsIt() throws Exception {
        try (var c = admin(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("CREATE ROLE bg02_future_owner NOLOGIN NOSUPERUSER NOBYPASSRLS");
                s.execute("ALTER TABLE user_roles OWNER TO bg02_future_owner");
                s.execute("CREATE POLICY fixture_authorized_owner ON user_roles FOR INSERT TO bg02_future_owner WITH CHECK (true)");
                // FORCE RLS owner administration is authorized by its existing permissive policy.
                s.execute("SET LOCAL ROLE bg02_future_owner");
                s.execute("INSERT INTO user_roles VALUES ('" + OTHER + "', 'PLATFORM_ADMIN')");
                s.execute("RESET ROLE");
                s.execute("DELETE FROM user_roles");
                s.execute("GRANT fixup_app TO bg02_future_owner WITH INHERIT TRUE, SET TRUE");
                s.execute("SAVEPOINT before_owner_write");
                s.execute("SET LOCAL ROLE bg02_future_owner");
                assertThatThrownBy(() -> s.execute("INSERT INTO user_roles VALUES ('" + OTHER + "', 'PLATFORM_ADMIN')"))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
                s.execute("ROLLBACK TO SAVEPOINT before_owner_write");
                assertThatThrownBy(() -> s.execute(Files.readString(Path.of("docs/pending-migrations/BG02/select_role_privileges.sql"))))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("P0001"));
            } finally { c.rollback(); }
        }
    }

    @Test
    void completeInventoryIncludesTransitiveMembersAndDistinguishesSetOnlyRuntime() throws Exception {
        try (var c = admin(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("CREATE ROLE bg02_middle NOLOGIN");
                s.execute("CREATE ROLE bg02_transitive NOLOGIN");
                s.execute("GRANT fixup_app TO bg02_middle WITH INHERIT TRUE, SET TRUE");
                s.execute("GRANT bg02_middle TO bg02_transitive WITH INHERIT TRUE, SET TRUE");
                var names = new HashSet<String>();
                try (var r = s.executeQuery("SELECT rolname FROM pg_roles WHERE NOT rolsuper AND NOT rolbypassrls AND rolname <> 'fixup_app' AND pg_has_role(oid, 'fixup_app', 'USAGE')")) {
                    while (r.next()) names.add(r.getString(1));
                }
                assertThat(names).containsExactlyInAnyOrder("bg02_middle", "bg02_transitive");
                assertThatThrownBy(() -> s.execute(Files.readString(Path.of("docs/pending-migrations/BG02/select_role_privileges.sql"))))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("P0001"));
            } finally { c.rollback(); }
            // Exercise the whole prepared read-only inventory on the committed isolated schema.
            boolean result = s.execute(Files.readString(Path.of("ops/blue-green/bg02-role-inheritance-review.sql")));
            boolean inventory = false;
            while (result || s.getUpdateCount() != -1) {
                if (result) {
                    try (var r = s.getResultSet()) {
                        if (r.getMetaData().getColumnCount() == 10) {
                            var found = new HashSet<String>();
                            while (r.next()) {
                                found.add(r.getString("rolname"));
                                if (r.getString("rolname").equals("bg02_review_runtime")) {
                                    assertThat(r.getBoolean("inherits_via_path")).isFalse();
                                    assertThat(r.getBoolean("effective_usage")).isFalse();
                                    assertThat(r.getBoolean("effective_set")).isTrue();
                                }
                            }
                            assertThat(found).containsExactlyInAnyOrder("fixup_green", "bg02_super_member", "bg02_bypass_member", "bg02_review_runtime");
                            inventory = true;
                        }
                    }
                }
                result = s.getMoreResults();
            }
            assertThat(inventory).isTrue();
        }
    }
}
