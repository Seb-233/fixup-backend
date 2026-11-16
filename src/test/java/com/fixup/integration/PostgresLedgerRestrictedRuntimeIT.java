package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SAF-01 con la aplicación conectada como un rol restringido.
 *
 * <p>Las demás pruebas de PostgreSQL conectan la aplicación como el superusuario del contenedor,
 * que se salta RLS: ahí una conciliación sin actor ve todo aunque esté mal diseñada. Aquí Flyway
 * migra como administrador y la aplicación corre como {@code fixup_runtime}: sin superusuario, sin
 * BYPASSRLS, sin ser dueña de las tablas y sin heredar ningún otro rol. Con FORCE ROW LEVEL SECURITY
 * ese rol, sin actor, no ve ni una fila del libro. La conciliación tiene que leerlo completo igual,
 * con filas de tres propietarios y dos técnicos, y no devolver un "cuadra" sobre cero filas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class PostgresLedgerRestrictedRuntimeIT {
    private static final String RUNTIME = "fixup_runtime";
    private static final String RUNTIME_PASSWORD = "runtime-only-for-this-container";
    private static final String ADMIN = "auth0|ledger-admin-restricted";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) throws Exception {
        // Solo el rol habitual de peticiones se aprovisiona antes de Flyway. El auditor y su
        // concesión al runtime deben venir de V48 y del script operativo, respectivamente.
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE ROLE fixup_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
                    CREATE ROLE %s LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
                        NOBYPASSRLS;
                    GRANT fixup_app TO %s WITH INHERIT FALSE, SET TRUE;
                    ALTER DEFAULT PRIVILEGES IN SCHEMA public
                        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %s;
                    """.formatted(RUNTIME, RUNTIME_PASSWORD, RUNTIME, RUNTIME));
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    /** La conexión de la aplicación: fixup_runtime. */
    @Autowired JdbcTemplate runtime;

    private static DriverManagerDataSource administratorSource;
    private static boolean readerProvisioned;
    private JdbcTemplate administrator;

    @BeforeEach
    void cleanAsAdministrator() throws Exception {
        if (administratorSource == null) {
            administratorSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword());
        }
        administrator = new JdbcTemplate(administratorSource);
        if (!readerProvisioned) {
            assertThat(administrator.queryForObject("SELECT pg_has_role(?, 'fixup_ledger_auditor', 'MEMBER')",
                    Boolean.class, RUNTIME)).as("Flyway no concede el lector al login de la aplicación").isFalse();
            provisionReader(RUNTIME);
            readerProvisioned = true;
        }
        SecurityContextHolder.clearContext();
        new IntegrationDatabaseCleaner(administrator, new DataSourceTransactionManager(administratorSource)).clean();
    }

    @AfterAll
    static void forget() {
        administratorSource = null;
        readerProvisioned = false;
    }

    @Test
    void provisioningIsRequiredRepeatableAndDoesNotInheritFinancialReads() throws Exception {
        administrator.execute("REVOKE fixup_ledger_auditor FROM " + RUNTIME);
        try {
            assertThatThrownBy(() -> asAuditor("SELECT COUNT(*) FROM repair_payments"))
                    .hasStackTraceContaining("permission denied");
        } finally {
            provisionReader(RUNTIME);
        }
        provisionReader(RUNTIME);
        assertThat(administrator.queryForMap("""
                SELECT m.inherit_option, m.set_option, m.admin_option FROM pg_auth_members m
                JOIN pg_roles granted ON granted.oid = m.roleid
                JOIN pg_roles member ON member.oid = m.member
                WHERE granted.rolname = 'fixup_ledger_auditor' AND member.rolname = ?
                """, RUNTIME)).containsEntry("inherit_option", false).containsEntry("set_option", true)
                .containsEntry("admin_option", false);
        assertThat(administrator.queryForObject(
                "SELECT pg_has_role('fixup_app', 'fixup_ledger_auditor', 'MEMBER')", Boolean.class)).isFalse();
        new LedgerFixture(administrator).seed();
        assertThat(asAuditor("SELECT COUNT(*) FROM repair_payments")).isEqualTo(7L);
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM repair_payments", Long.class)).isZero();
        assertThat(runtime.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'CREATE')",
                Boolean.class)).isFalse();
        assertThat(administrator.queryForObject(
                "SELECT COUNT(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = ?",
                Integer.class, RUNTIME)).isZero();
    }

    @Test
    void provisioningRejectsMissingUnknownAndPrivilegedLogins() {
        for (String login : java.util.List.of("", "unknown-runtime", "fixup_app", POSTGRES.getUsername(),
                "fixup_runtime; GRANT fixup_ledger_auditor TO fixup_app")) {
            assertThatThrownBy(() -> provisionReader(login)).as(login).hasStackTraceContaining("PSQLException");
        }
        assertThat(administrator.queryForObject(
                "SELECT pg_has_role('fixup_app', 'fixup_ledger_auditor', 'MEMBER')", Boolean.class)).isFalse();
    }

    @Test
    void theApplicationRoleIsRestrictedAndSeesNoLedgerRowsWithoutAnActor() {
        new LedgerFixture(administrator).seed();

        var role = runtime.queryForMap(
                "SELECT rolsuper, rolbypassrls, rolinherit FROM pg_roles WHERE rolname = current_user");
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME);
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
        assertThat(runtime.queryForObject("""
                SELECT bool_and(c.relrowsecurity AND c.relforcerowsecurity) FROM pg_class c
                WHERE c.relname IN ('repair_payments', 'fixer_earnings', 'payouts', 'payout_entries', 'owner_debts',
                    'cash_settlements', 'simulated_payment_attempts')
                """, Boolean.class)).isTrue();

        // El riesgo que señaló la revisión: así leía la conciliación antes, y sobre cero filas "cuadraba".
        assertThat(administrator.queryForObject("SELECT COUNT(*) FROM repair_payments", Long.class)).isEqualTo(7);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM repair_payments", Long.class)).isZero();
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM fixer_earnings", Long.class)).isZero();
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM payouts", Long.class)).isZero();
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM owner_debts", Long.class)).isZero();
    }

    @Test
    void theReconciliationReadsEveryOwnersRowsAndBalances() throws Exception {
        admin();
        new LedgerFixture(administrator).seed();

        JsonNode report = reconcile();

        assertThat(report.get("consistent").asBoolean()).as(report.toPrettyString()).isTrue();
        JsonNode scanned = report.get("scanned");
        assertThat(scanned.get("payments").asLong()).isEqualTo(7);
        assertThat(scanned.get("paymentAttempts").asLong()).isEqualTo(8);
        assertThat(scanned.get("earnings").asLong()).isEqualTo(6);
        assertThat(scanned.get("payouts").asLong()).isEqualTo(2);
        assertThat(scanned.get("payoutEntries").asLong()).isEqualTo(2);
        assertThat(scanned.get("debts").asLong()).isEqualTo(2);
        assertThat(scanned.get("cashSettlements").asLong()).isEqualTo(3);
        assertThat(report.get("totals").get("collected").asLong())
                .isEqualTo(LedgerFixture.SERVICE_COLLECTED + LedgerFixture.DEBT_COLLECTED);
        assertThat(report.get("totals").get("earningsGross").asLong()).isEqualTo(LedgerFixture.EARNINGS_GROSS);
    }

    @Test
    void aRealDiscrepancyIsNotHiddenByRowLevelSecurity() throws Exception {
        admin();
        var ledger = new LedgerFixture(administrator).seed();
        // Ingreso de un técnico que no es quien pide la conciliación ni tiene relación con él.
        ledger.tamper("DELETE FROM fixer_earnings WHERE id = ?", ledger.paidDebt.earning);

        JsonNode report = reconcile();

        assertThat(report.get("consistent").asBoolean()).isFalse();
        assertThat(report.get("findings").findValuesAsText("code"))
                .containsExactlyInAnyOrder("DEBT_PAYMENT_NOT_APPLIED", "PAYOUT_ENTRY_MISMATCH");
        assertThat(report.get("findings").toString()).contains(ledger.paidDebt.payment.toString(),
                ledger.paidDebt.earning.toString());
    }

    /** Mínimo privilegio: el rol lector solo lee montos, estados e identificadores del libro. */
    @Test
    void theAuditorRoleReadsOnlyLedgerAmountsAndCannotWrite() {
        new LedgerFixture(administrator).seed();

        assertThat(asAuditor("SELECT COUNT(*) FROM repair_payments")).isEqualTo(7L);
        assertThatThrownBy(() -> asAuditor("SELECT COUNT(owner_user_id) FROM repair_payments"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asAuditor("SELECT COUNT(reference) FROM repair_payments"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asAuditor("SELECT COUNT(*) FROM users"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asAuditor("SELECT COUNT(*) FROM repair_requests"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asAuditor("WITH gone AS (DELETE FROM fixer_earnings RETURNING 1) SELECT COUNT(*) FROM gone"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asAuditor(
                "WITH moved AS (UPDATE fixer_earnings SET net_amount = net_amount RETURNING 1) SELECT COUNT(*) FROM moved"))
                .hasStackTraceContaining("permission denied");
        assertThat(administrator.queryForMap("""
                SELECT rolcanlogin, rolsuper, rolbypassrls, rolinherit FROM pg_roles
                WHERE rolname = 'fixup_ledger_auditor'
                """)).containsEntry("rolcanlogin", false).containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false).containsEntry("rolinherit", false);
    }

    /** Corre una consulta como lo hace la conciliación: la conexión de la aplicación con SET LOCAL ROLE. */
    private void provisionReader(String login) throws Exception {
        String sql = Files.readString(Path.of("scripts", "provision-ledger-reader.sql"));
        administrator.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var parameter = connection.prepareStatement("SELECT set_config('fixup.ledger_runtime_role', ?, false)")) {
                parameter.setString(1, login);
                parameter.execute();
            }
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    /** El rol se restablece al terminar la transacción y no contamina el pool. */
    private Long asAuditor(String sql) {
        return runtime.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE fixup_ledger_auditor");
                try (var rows = statement.executeQuery(sql)) {
                    rows.next();
                    return rows.getLong(1);
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    private JsonNode reconcile() throws Exception {
        var result = mvc.perform(get("/administration/payments/reconciliation").with(identity()))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private void admin() throws Exception {
        var created = mvc.perform(post("/auth/bootstrap").with(identity())).andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        administrator.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", id);
    }

    private static RequestPostProcessor identity() {
        return jwt().jwt(token -> token.subject(ADMIN).claim("email", "ledger-admin@example.test")
                .claim("name", "Synthetic User"));
    }
}
