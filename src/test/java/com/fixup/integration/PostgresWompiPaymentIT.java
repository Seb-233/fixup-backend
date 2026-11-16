package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import com.fixup.testsupport.PropertyFixtures;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * INT-01: Wompi de punta a punta contra PostgreSQL con RLS y triggers reales. La API de Wompi es un
 * servidor HTTP local que responde como el sandbox; el resto (firma, verificación, libro, trabajo,
 * notificaciones) es el código real del backend.
 *
 * <p>El backend se conecta con privilegios equivalentes al runtime de Green: otro usuario aplica las
 * migraciones y es dueño de las tablas; el de ejecución no es superusuario, no tiene BYPASSRLS ni DDL
 * y solo es miembro de fixup_app. Los datos de cada prueba se preparan y se comprueban con una
 * conexión administrativa aparte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class PostgresWompiPaymentIT {
    private static final String EVENTS_SECRET = "test_events_it";
    private static final String INTEGRITY_SECRET = "test_integrity_it";
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final Map<String, String> TRANSACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> CREATED = new ConcurrentHashMap<>();
    /** Fallas simuladas de Wompi por id de transacción: "500" o "SLOW" (más que el read-timeout). */
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();
    private static final HttpServer WOMPI = fakeWompi();

    /** El backend se conecta como fixup_runtime (ver green_runtime_roles.sql), no como superusuario. */
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("db/green_runtime_roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "fixup_runtime");
        r.add("spring.datasource.password", () -> "runtime-it");
        // Las migraciones corren con otro usuario, como el Job de migración de Green.
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("fixup.payments.wompi.public-key", () -> "pub_test_it");
        r.add("fixup.payments.wompi.private-key", () -> "prv_test_it");
        r.add("fixup.payments.wompi.integrity-secret", () -> INTEGRITY_SECRET);
        r.add("fixup.payments.wompi.events-secret", () -> EVENTS_SECRET);
        r.add("fixup.payments.wompi.return-origins", () -> "http://fixup.localhost:4200");
        r.add("fixup.payments.wompi.read-timeout", () -> "1s");
        r.add("fixup.payments.wompi.api-base-url", () -> "http://127.0.0.1:" + WOMPI.getAddress().getPort() + "/v1");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    /** Conexión del backend: fixup_runtime. Solo se usa para demostrar con qué privilegios corre. */
    @Autowired JdbcTemplate runtime;
    /** Conexión administrativa (dueña de las tablas) para preparar datos y comprobar resultados. */
    private JdbcTemplate jdbc;
    private IntegrationDatabaseCleaner cleaner;
    private PlatformTransactionManager transactions;
    private UUID owner, fixer, manager, property, request, quotation, payment;

    @BeforeEach void setup() {
        var admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection(admin), true));
        transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        cleaner = new IntegrationDatabaseCleaner(jdbc, transactions);
        cleaner.clean(); TRANSACTIONS.clear(); CREATED.clear(); FAILURES.clear();
    }
    @AfterEach void cleanup() {
        cleaner.clean();
        ((SingleConnectionDataSource) jdbc.getDataSource()).destroy();
    }

    private static java.sql.Connection connection(DriverManagerDataSource source) {
        try {
            return source.getConnection();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void approvedCheckoutOpensTheJobOnceThroughReturnAndWebhook() throws Exception {
        accepted();
        mvc.perform(get("/payments/wompi").with(identity("owner"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.environment").value("sandbox"));
        var checkout = checkout("owner");
        String reference = checkout.get("reference").asText();
        assertThat(reference).startsWith(paymentReference() + "-");
        Map<String, String> params = query(checkout.get("checkoutUrl").asText());
        assertThat(params).containsEntry("amount-in-cents", "10000000").containsEntry("currency", "COP")
                .containsEntry("public-key", "pub_test_it").containsEntry("reference", reference)
                .containsEntry("redirect-url", "http://fixup.localhost:4200/payments/" + payment)
                .containsEntry("signature:integrity", sha256(reference + "10000000COP" + INTEGRITY_SECRET));

        transaction("11111-1-APPROVED", reference, 10_000_000, "APPROVED");
        confirm("owner", "11111-1-APPROVED").andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("APPROVED"))
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.attempt.instrument").value("WOMPI"))
                .andExpect(jsonPath("$.attempt.gatewayTransactionId").value("11111-1-APPROVED"));
        var before = snapshot();
        confirm("owner", "11111-1-APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        webhook("11111-1-APPROVED", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);

        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM fixer_earnings", String.class)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT scenario FROM simulated_payment_attempts", String.class)).isEqualTo("WOMPI_CARD");
        assertThat(jdbc.queryForObject("SELECT attempted_by_user_id FROM simulated_payment_attempts", UUID.class)).isEqualTo(owner);
        checkoutRequest("owner").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_APPROVED"));
    }

    @Test
    void declinedThenApprovedOnANewReference() throws Exception {
        accepted();
        String first = checkout("owner").get("reference").asText();
        transaction("22222-1-DECLINED", first, 10_000_000, "DECLINED");
        confirm("owner", "22222-1-DECLINED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("DECLINED"))
                .andExpect(jsonPath("$.attempt.failureReason").value("Wompi rechazó la transacción: Fondos insuficientes"));
        assertThat(count("jobs")).isZero();

        String second = checkout("owner").get("reference").asText();
        assertThat(second).isNotEqualTo(first);
        transaction("22222-2-PENDING", second, 10_000_000, "PENDING");
        confirm("owner", "22222-2-PENDING").andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("PENDING")).andExpect(jsonPath("$.attempt").doesNotExist());
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
        transaction("22222-2-PENDING", second, 10_000_000, "APPROVED");
        confirm("owner", "22222-2-PENDING").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        assertThat(count("simulated_payment_attempts")).isEqualTo(2);
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("APPROVED");
    }

    @Test
    void signedWebhookAloneSettlesThePaymentAsTheCheckoutOpener() throws Exception {
        accepted();
        String reference = checkout("manager").get("reference").asText();
        transaction("33333-1-APPROVED", reference, 10_000_000, "APPROVED");

        webhook("33333-1-APPROVED", "APPROVED", 10_000_000, "wrong-secret").andExpect(status().isUnauthorized());
        assertThat(count("simulated_payment_attempts")).isZero();
        webhook("33333-1-APPROVED", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT attempted_by_user_id FROM simulated_payment_attempts", UUID.class)).isEqualTo(manager);
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT changed_by_user_id FROM job_status_log", UUID.class)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'PAYMENT_APPROVED'", Integer.class)).isEqualTo(3);
    }

    @Test
    void rejectsForeignTransactionsAmountsOriginsAndPayers() throws Exception {
        accepted();
        String reference = checkout("owner").get("reference").asText();
        transaction("44444-1-CHEAP", reference, 100, "APPROVED");
        confirm("owner", "44444-1-CHEAP").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WOMPI_AMOUNT_MISMATCH"));
        transaction("44444-2-FOREIGN", "OTRA-TIENDA-1", 10_000_000, "APPROVED");
        confirm("owner", "44444-2-FOREIGN").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WOMPI_REFERENCE_UNKNOWN"));
        confirm("owner", "no-existe").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WOMPI_TRANSACTION_NOT_FOUND"));

        mvc.perform(post("/payments/" + payment + "/wompi/checkout").with(identity("owner")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"returnUrl\":\"https://evil.example/payments/" + payment + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETURN_ORIGIN_NOT_ALLOWED"));
        for (String local : new String[] {"http://localhost:4200", "http://127.0.0.1:4200", "http://[::1]:4200"}) {
            mvc.perform(post("/payments/" + payment + "/wompi/checkout").with(identity("owner")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"returnUrl\":\"" + local + "/payments/" + payment + "\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WOMPI_RETURN_URL_LOCAL"));
        }

        provision("stranger", "OWNER");
        checkoutRequest("stranger").andExpect(status().is4xxClientError());
        transaction("44444-3-OK", reference, 10_000_000, "APPROVED");
        confirm("stranger", "44444-3-OK").andExpect(status().is4xxClientError());
        provision("tenant", "TENANT");
        checkoutRequest("tenant").andExpect(status().is4xxClientError());
        assertThat(count("simulated_payment_attempts")).isZero();
        assertThat(count("jobs")).isZero();

        mvc.perform(post("/payments/" + payment + "/attempts").with(identity("owner")).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + paymentReference() + "\",\"amount\":100000,\"instrument\":\"WOMPI\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_INSTRUMENT"));
    }

    @Test
    void adminSeesWompiTransactionsReconciledWithFixUp() throws Exception {
        accepted();
        provision("admin", "PLATFORM_ADMIN");
        String first = checkout("owner").get("reference").asText();
        String second = checkout("owner").get("reference").asText();
        transaction("55555-1-APPROVED", first, 10_000_000, "APPROVED");
        transaction("55555-2-PENDING", second, 10_000_000, "PENDING");
        transaction("55555-3-FOREIGN", "OTRA-TIENDA-1", 5_000_000, "APPROVED");
        transaction("55555-4-CHEAP", second, 100, "DECLINED");

        var all = json(transactions("admin", "?status=ALL&from=2026-11-01&until=2026-11-14").andExpect(status().isOk()).andReturn());
        assertThat(all.get("environment").asText()).isEqualTo("sandbox");
        assertThat(all.get("totalResults").asLong()).isEqualTo(4);
        Map<String, JsonNode> byId = new java.util.HashMap<>();
        all.get("items").forEach(item -> byId.put(item.get("transactionId").asText(), item));
        assertThat(byId.get("55555-1-APPROVED").get("reconciliation").asText()).isEqualTo("NOT_RECORDED");
        assertThat(byId.get("55555-1-APPROVED").get("paymentId").asText()).isEqualTo(payment.toString());
        assertThat(byId.get("55555-1-APPROVED").get("paymentStatus").asText()).isEqualTo("PENDING");
        assertThat(byId.get("55555-2-PENDING").get("reconciliation").asText()).isEqualTo("PENDING");
        assertThat(byId.get("55555-3-FOREIGN").get("reconciliation").asText()).isEqualTo("FOREIGN");
        assertThat(byId.get("55555-3-FOREIGN").get("paymentId").isNull()).isTrue();
        assertThat(byId.get("55555-4-CHEAP").get("reconciliation").asText()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(all.get("items").get(0).get("transactionId").asText()).isEqualTo("55555-4-CHEAP");
        assertThat(all.toString()).doesNotContain("comprador@example.test");

        confirm("owner", "55555-1-APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        transaction("55555-5-TWICE", second, 10_000_000, "APPROVED");
        var approved = json(transactions("admin", "").andExpect(status().isOk()).andReturn());
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        byId.clear();
        approved.get("items").forEach(item -> byId.put(item.get("transactionId").asText(), item));
        assertThat(byId).containsOnlyKeys("55555-1-APPROVED", "55555-3-FOREIGN", "55555-5-TWICE");
        assertThat(byId.get("55555-1-APPROVED").get("reconciliation").asText()).isEqualTo("RECORDED");
        assertThat(byId.get("55555-1-APPROVED").get("recordedStatus").asText()).isEqualTo("APPROVED");
        assertThat(byId.get("55555-5-TWICE").get("reconciliation").asText()).isEqualTo("DUPLICATE");
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);

        transactions("owner", "").andExpect(status().isForbidden());
        transactions("admin", "?from=2026-11-14&until=2026-11-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSACTION_FILTER"));
        transactions("admin", "?status=PAID").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSACTION_FILTER"));
        transactions("admin", "?size=500").andExpect(status().isConflict());
    }

    @Test
    void rlsLetsOnlyTheCurrentPayerOpenAndReadCheckoutsAndNobodyChangeThem() throws Exception {
        accepted();
        UUID stranger = provision("stranger", "OWNER");
        UUID tenant = provision("tenant", "TENANT");
        UUID admin = provision("admin", "PLATFORM_ADMIN");
        UUID otherManager = provision("other-manager", "REAL_ESTATE_MANAGER");
        long cents = 10_000_000;
        String sql = "INSERT INTO wompi_checkouts(reference,payment_id,amount_in_cents,created_by_user_id,created_at) "
                + "VALUES (?,?,?,?,CURRENT_TIMESTAMP)";

        assertThat(asActor(owner, "OWNER", () -> jdbc.update(sql, "RLS-OWNER", payment, cents, owner))).isEqualTo(1);
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> jdbc.update(sql, "RLS-MANAGER", payment, cents, manager))).isEqualTo(1);
        jdbc.update(sql, "RLS-OWNER", payment, cents, owner);
        jdbc.update(sql, "RLS-MANAGER", payment, cents, manager);

        assertThat(asActor(owner, "OWNER", () -> count("wompi_checkouts"))).isEqualTo(2);
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> count("wompi_checkouts"))).isEqualTo(2);
        assertThat(asActor(admin, "PLATFORM_ADMIN", () -> count("wompi_checkouts"))).isEqualTo(2);
        for (var outsider : Map.of(stranger, "OWNER", tenant, "TENANT", fixer, "FIXER", otherManager, "REAL_ESTATE_MANAGER").entrySet()) {
            assertThat(asActor(outsider.getKey(), outsider.getValue(), () -> count("wompi_checkouts"))).as(outsider.getValue()).isZero();
        }

        // Alta rechazada: a nombre de otro, monto distinto, terceros y administración.
        assertThatThrownBy(() -> asActor(owner, "OWNER", () -> jdbc.update(sql, "RLS-X1", payment, cents, manager)))
                .hasStackTraceContaining("row-level security");
        assertThatThrownBy(() -> asActor(owner, "OWNER", () -> jdbc.update(sql, "RLS-X2", payment, 100L, owner)))
                .hasStackTraceContaining("row-level security");
        assertThatThrownBy(() -> asActor(stranger, "OWNER", () -> jdbc.update(sql, "RLS-X3", payment, cents, stranger)))
                .hasStackTraceContaining("row-level security");
        assertThatThrownBy(() -> asActor(otherManager, "REAL_ESTATE_MANAGER", () -> jdbc.update(sql, "RLS-X4", payment, cents, otherManager)))
                .hasStackTraceContaining("row-level security");
        assertThatThrownBy(() -> asActor(admin, "PLATFORM_ADMIN", () -> jdbc.update(sql, "RLS-X5", payment, cents, admin)))
                .hasStackTraceContaining("row-level security");
        // Nadie modifica ni borra: el rol de ejecución no tiene esos privilegios.
        assertThatThrownBy(() -> asActor(owner, "OWNER", () -> jdbc.update("UPDATE wompi_checkouts SET amount_in_cents = 1")))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asActor(admin, "PLATFORM_ADMIN", () -> jdbc.update("DELETE FROM wompi_checkouts")))
                .hasStackTraceContaining("permission denied");
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('fixup_app','wompi_checkouts','UPDATE') "
                + "OR has_table_privilege('fixup_app','wompi_checkouts','DELETE') "
                + "OR has_table_privilege('fixup_app','wompi_checkouts','TRUNCATE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE relname = 'wompi_checkouts'", Boolean.class)).isTrue();

        // Revocada la delegación, el administrador inmobiliario deja de ver y de poder abrir checkouts.
        jdbc.update("UPDATE properties SET manager_user_id = NULL WHERE id = ?", property);
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> count("wompi_checkouts"))).isZero();
        // Con el pago aprobado nadie abre otro checkout.
        jdbc.update("UPDATE properties SET manager_user_id = ? WHERE id = ?", manager, property);
        transaction("77777-1-APPROVED", "RLS-OWNER", cents, "APPROVED");
        confirm("owner", "77777-1-APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        assertThatThrownBy(() -> asActor(owner, "OWNER", () -> jdbc.update(sql, "RLS-X6", payment, cents, owner)))
                .hasStackTraceContaining("row-level security");
    }

    @Test
    void theWebhookAppliesTheResultUnderTheOpenersIsolationNotAsAnUnrestrictedProcess() throws Exception {
        accepted();
        provision("admin", "PLATFORM_ADMIN");
        String reference = checkout("manager").get("reference").asText();
        transaction("88888-1-APPROVED", reference, 10_000_000, "APPROVED");
        // El propietario revoca la delegación antes de que llegue el evento.
        jdbc.update("UPDATE properties SET manager_user_id = NULL WHERE id = ?", property);

        webhook("88888-1-APPROVED", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        assertThat(count("simulated_payment_attempts")).as("el webhook no salta el aislamiento").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("PENDING");
        var reconciliation = json(mvc.perform(get("/payments/wompi/transactions?status=ALL&from=2026-11-01&until=2026-11-14")
                .with(identity("admin"))).andExpect(status().isOk()).andReturn());
        assertThat(reconciliation.at("/items/0/reconciliation").asText()).isEqualTo("NOT_RECORDED");

        // El pagador vigente sí puede registrarlo al volver del checkout.
        confirm("owner", "88888-1-APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT attempted_by_user_id FROM simulated_payment_attempts", UUID.class)).isEqualTo(owner);
        assertThat(count("jobs")).isEqualTo(1);
    }

    @Test
    void theBackendRunsAsAGreenEquivalentRuntimeAndResolvesCheckoutsOnlyThroughTheBoundedFunction() throws Exception {
        accepted();
        String reference = checkout("owner").get("reference").asText();

        // Privilegios reales de la conexión del backend: ni superusuario, ni BYPASSRLS, ni dueña, ni DDL.
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("fixup_runtime");
        assertThat(runtime.queryForObject("SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb "
                + "FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT tableowner FROM pg_tables WHERE tablename = 'wompi_checkouts'",
                String.class)).isNotIn("fixup_runtime", "fixup_app", "fixup_wompi_checkout_resolver");
        assertThat(runtime.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class)).isFalse();

        // Sin actor, el runtime no ve ninguna fila con un SELECT directo: no hay lectura general.
        assertThat(count("wompi_checkouts")).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT count(*) FROM wompi_checkouts", Integer.class)).isZero();
        // La función acotada sí resuelve la referencia, y solo devuelve quién la abrió.
        assertThat(runtime.queryForObject("SELECT app_wompi_checkout_opener(?)", UUID.class, reference)).isEqualTo(owner);
        assertThat(runtime.queryForObject("SELECT app_wompi_checkout_opener(?)", UUID.class, "OTRA-TIENDA-1")).isNull();
        // Una sesión de usuario (cualquier rol) no puede usarla para resolver referencias.
        UUID admin = provision("admin", "PLATFORM_ADMIN");
        for (var session : Map.of(owner, "OWNER", fixer, "FIXER", admin, "PLATFORM_ADMIN").entrySet()) {
            assertThatThrownBy(() -> asActor(session.getKey(), session.getValue(),
                    () -> jdbc.queryForObject("SELECT app_wompi_checkout_opener(?)", UUID.class, reference)))
                    .as(session.getValue()).hasStackTraceContaining("not available to user sessions");
        }

        // El rol dueño de la función no puede entrar, no hereda, no tiene miembros y solo lee dos columnas.
        assertThat(jdbc.queryForObject("SELECT rolcanlogin OR rolinherit OR rolsuper OR rolbypassrls FROM pg_roles "
                + "WHERE rolname = 'fixup_wompi_checkout_resolver'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid IN (m.roleid, m.member) "
                + "WHERE r.rolname = 'fixup_wompi_checkout_resolver'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT has_column_privilege('fixup_wompi_checkout_resolver','wompi_checkouts','payment_id','SELECT') "
                + "OR has_column_privilege('fixup_wompi_checkout_resolver','wompi_checkouts','amount_in_cents','SELECT') "
                + "OR has_table_privilege('fixup_wompi_checkout_resolver','wompi_checkouts','INSERT') "
                + "OR has_table_privilege('fixup_wompi_checkout_resolver','repair_payments','SELECT')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT proacl::text FROM pg_proc WHERE proname = 'app_wompi_checkout_opener'", String.class))
                .contains("fixup_app=X/").doesNotContain("{=X/").doesNotContain(",=X/");
    }

    @Test
    void webhookWithoutBrowserReturnRecordsTheApprovedPaymentExactlyOnce() throws Exception {
        accepted();
        String reference = checkout("owner").get("reference").asText();
        transaction("31313-1-TX", reference, 10_000_000, "PENDING");
        var untouched = snapshot();

        // Sin firma, con otra llave o con el contenido alterado después de firmar: 401 y nada cambia.
        signedWebhook("31313-1-TX", "APPROVED", 10_000_000, null).andExpect(status().isUnauthorized());
        webhook("31313-1-TX", "APPROVED", 10_000_000, "wrong-secret").andExpect(status().isUnauthorized());
        signedWebhook("31313-1-TX", "APPROVED", 10_000_000, sha256("31313-1-TX" + "APPROVED" + 100 + TIMESTAMP + EVENTS_SECRET))
                .andExpect(status().isUnauthorized());
        // PENDING: evento auténtico, pero Wompi todavía no resuelve; no se registra nada.
        webhook("31313-1-TX", "PENDING", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(untouched);

        // Wompi aprueba y avisa. El pagador nunca vuelve del checkout.
        transaction("31313-1-TX", reference, 10_000_000, "APPROVED");
        webhook("31313-1-TX", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForMap("SELECT instrument, resulting_status, gateway_transaction_id, attempted_by_user_id FROM simulated_payment_attempts"))
                .containsEntry("instrument", "WOMPI").containsEntry("resulting_status", "APPROVED")
                .containsEntry("gateway_transaction_id", "31313-1-TX").containsEntry("attempted_by_user_id", owner);
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
        assertThat(count("fixer_earnings")).isEqualTo(1);
        assertThat(count("jobs")).isEqualTo(1);

        // Reenvíos del mismo evento y el retorno tardío del pagador no duplican nada.
        var recorded = snapshot();
        webhook("31313-1-TX", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        webhook("31313-1-TX", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        confirm("owner", "31313-1-TX").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        assertThat(snapshot()).isEqualTo(recorded);
    }

    @Test
    void webhookForForeignOrMismatchedTransactionsRecordsNothingAndLeavesThemInReconciliation() throws Exception {
        accepted();
        provision("admin", "PLATFORM_ADMIN");
        String reference = checkout("owner").get("reference").asText();
        transaction("41414-1-FOREIGN", "OTRA-TIENDA-1", 10_000_000, "APPROVED");
        transaction("41414-2-CHEAP", reference, 100, "APPROVED");
        transaction("41414-3-USD", reference, 10_000_000, "APPROVED", "USD");
        var untouched = snapshot();

        webhook("41414-1-FOREIGN", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        webhook("41414-2-CHEAP", "APPROVED", 100, EVENTS_SECRET).andExpect(status().isOk());
        webhook("41414-3-USD", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        confirm("owner", "41414-3-USD").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WOMPI_AMOUNT_MISMATCH"));
        assertThat(snapshot()).isEqualTo(untouched);

        Map<String, String> reconciliation = new java.util.HashMap<>();
        json(transactions("admin", "?status=ALL&from=2026-11-01&until=2026-11-14").andExpect(status().isOk()).andReturn())
                .get("items").forEach(item -> reconciliation.put(item.get("transactionId").asText(), item.get("reconciliation").asText()));
        assertThat(reconciliation).containsEntry("41414-1-FOREIGN", "FOREIGN").containsEntry("41414-2-CHEAP", "AMOUNT_MISMATCH")
                .containsEntry("41414-3-USD", "AMOUNT_MISMATCH");
    }

    @Test
    void webhookFromAnOpenerWhoLostTheRoleIsNotRecordedAndThePayerCanStillSettleIt() throws Exception {
        accepted();
        String reference = checkout("manager").get("reference").asText();
        transaction("51515-1-APPROVED", reference, 10_000_000, "APPROVED");
        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND role = 'REAL_ESTATE_MANAGER'", manager);
        var untouched = snapshot();

        webhook("51515-1-APPROVED", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(untouched);

        // Revisión: el pagador vigente registra la transacción autorizada que quedó sin registrar.
        confirm("owner", "51515-1-APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
        assertThat(count("jobs")).isEqualTo(1);
    }

    @Test
    void returnAfterAnExpiredSessionChangesNothingAndTheWebhookStillSettlesThePayment() throws Exception {
        accepted();
        String reference = checkout("owner").get("reference").asText();
        transaction("61616-1-APPROVED", reference, 10_000_000, "APPROVED");
        var untouched = snapshot();

        // El pagador vuelve del checkout con la sesión vencida: sin token no se verifica ni se abre nada.
        mvc.perform(post("/payments/" + payment + "/wompi/transactions/61616-1-APPROVED")).andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/" + payment + "/wompi/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"returnUrl\":\"http://fixup.localhost:4200/payments/" + payment + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/payments/wompi/transactions")).andExpect(status().isUnauthorized());
        assertThat(snapshot()).isEqualTo(untouched);

        // El webhook no depende de esa sesión.
        webhook("61616-1-APPROVED", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("APPROVED");
        var recorded = snapshot();
        // Al iniciar sesión otra vez, la verificación pendiente del retorno es idempotente.
        confirm("owner", "61616-1-APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.attempt.gatewayTransactionId").value("61616-1-APPROVED"));
        assertThat(snapshot()).isEqualTo(recorded);
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
    }

    @Test
    void anAuthenticEventForATransactionTheApiDoesNotReturnYetAsksWompiToRetry() throws Exception {
        accepted();
        String reference = checkout("owner").get("reference").asText();
        var untouched = snapshot();
        webhook("71717-1-LATE", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WOMPI_TRANSACTION_NOT_READY"));
        assertThat(snapshot()).isEqualTo(untouched);

        transaction("71717-1-LATE", reference, 10_000_000, "APPROVED");
        webhook("71717-1-LATE", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("APPROVED");
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
    }

    @Test
    void concurrentReturnsAndWebhooksRecordTheTransactionExactlyOnce() throws Exception {
        accepted();
        String reference = checkout("owner").get("reference").asText();
        transaction("99999-1-APPROVED", reference, 10_000_000, "APPROVED");
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = new java.util.ArrayList<Callable<Integer>>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> confirm("owner", "99999-1-APPROVED").andReturn().getResponse().getStatus());
                calls.add(() -> webhook("99999-1-APPROVED", "APPROVED", 10_000_000, EVENTS_SECRET).andReturn().getResponse().getStatus());
            }
            for (var outcome : pool.invokeAll(calls)) {
                assertThat(outcome.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("fixer_earnings")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'PAYMENT_APPROVED'", Integer.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(DISTINCT recipient_user_id) FROM notifications WHERE type = 'PAYMENT_APPROVED'", Integer.class));
    }

    @Test
    void wompiDownOrSlowLeavesThePaymentUntouchedAndTheWebhookAsksForARetry() throws Exception {
        accepted();
        String reference = checkout("owner").get("reference").asText();
        transaction("12121-1-APPROVED", reference, 10_000_000, "APPROVED");
        var before = snapshot();
        for (String failure : List.of("500", "SLOW")) {
            FAILURES.put("12121-1-APPROVED", failure);
            confirm("owner", "12121-1-APPROVED").andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_UNAVAILABLE"));
            // Un 503 hace que Wompi reenvíe el evento más tarde.
            webhook("12121-1-APPROVED", "APPROVED", 10_000_000, EVENTS_SECRET).andExpect(status().isServiceUnavailable());
            assertThat(snapshot()).as(failure).isEqualTo(before);
        }
        FAILURES.clear();
        confirm("owner", "12121-1-APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("APPROVED"));
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
    }

    @Test
    void theSimulatedGatewayKeepsWorkingWithWompiEnabled() throws Exception {
        accepted();
        mvc.perform(post("/payments/" + payment + "/attempts").with(identity("owner")).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + paymentReference() + "\",\"amount\":100000,\"instrument\":\"SANDBOX_CARD\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT instrument FROM simulated_payment_attempts", String.class)).isEqualTo("SANDBOX_CARD");
    }

    private <T> T asActor(UUID id, String role, Supplier<T> action) {
        var template = new TransactionTemplate(transactions);
        return template.execute(tx -> {
            jdbc.execute("SET LOCAL ROLE fixup_app");
            jdbc.queryForObject("SELECT set_config('app.current_user_id', ?, true)", String.class, id.toString());
            jdbc.queryForObject("SELECT set_config('app.current_user_roles', ?, true)", String.class, role);
            T result = action.get();
            tx.setRollbackOnly();
            return result;
        });
    }

    private ResultActions transactions(String actor, String query) throws Exception {
        return mvc.perform(get("/payments/wompi/transactions" + query).with(identity(actor)));
    }

    private JsonNode checkout(String actor) throws Exception {
        return json(checkoutRequest(actor).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions checkoutRequest(String actor) throws Exception {
        return mvc.perform(post("/payments/" + payment + "/wompi/checkout").with(identity(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"returnUrl\":\"http://fixup.localhost:4200/payments/" + payment + "\"}"));
    }

    private ResultActions confirm(String actor, String transactionId) throws Exception {
        return mvc.perform(post("/payments/" + payment + "/wompi/transactions/" + transactionId).with(identity(actor)));
    }

    private ResultActions webhook(String id, String status, long cents, String secret) throws Exception {
        return signedWebhook(id, status, cents, sha256(id + status + cents + TIMESTAMP + secret));
    }

    /** Evento con el checksum dado; nulo lo envía sin firma (ni cabecera ni campo). */
    private ResultActions signedWebhook(String id, String status, long cents, String checksum) throws Exception {
        long timestamp = TIMESTAMP;
        String body = """
                {"event":"transaction.updated","data":{"transaction":{"id":"%s","amount_in_cents":%d,
                "reference":"ignorada","currency":"COP","payment_method_type":"CARD","status":"%s"}},
                "environment":"test","signature":{"properties":["transaction.id","transaction.status",
                "transaction.amount_in_cents"],"checksum":"%s"},"timestamp":%d,"sent_at":"2026-11-14T12:00:00.000Z"}
                """.formatted(id, cents, status, checksum == null ? "" : checksum, timestamp);
        var event = post("/payments/wompi/events").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(checksum == null ? event : event.header("X-Event-Checksum", checksum));
    }

    private static void transaction(String id, String reference, long cents, String status) {
        transaction(id, reference, cents, status, "COP");
    }

    private static void transaction(String id, String reference, long cents, String status, String currency) {
        // created_at crece con cada alta (para comprobar el orden) y no cambia cuando la transacción se resuelve.
        String created = CREATED.computeIfAbsent(id,
                ignored -> java.time.Instant.parse("2026-11-14T15:00:00Z").plusSeconds(CREATED.size()).toString());
        TRANSACTIONS.put(id, """
                {"id":"%s","reference":"%s","amount_in_cents":%d,"currency":"%s","status":"%s",
                "payment_method_type":"CARD","status_message":%s,"created_at":"%s","customer_email":"comprador@example.test"}
                """.formatted(id, reference, cents, currency, status, status.equals("DECLINED") ? "\"Fondos insuficientes\"" : "null", created));
    }

    private static HttpServer fakeWompi() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/transactions/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/v1/transactions/".length());
                String failure = FAILURES.get(id);
                if ("SLOW".equals(failure)) {
                    try { Thread.sleep(2_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } else if ("500".equals(failure)) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                boolean authorized = "Bearer prv_test_it".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = authorized && TRANSACTIONS.containsKey(id) ? "{\"data\":" + TRANSACTIONS.get(id) + "}" : null;
                byte[] bytes = (body == null ? "{\"error\":{\"type\":\"NOT_FOUND_ERROR\"}}" : body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.createContext("/v1/transactions", exchange -> {
                Map<String, String> q = exchange.getRequestURI().getRawQuery() == null ? Map.of()
                        : query("?" + exchange.getRequestURI().getRawQuery());
                boolean authorized = "Bearer prv_test_it".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean valid = q.containsKey("from_date") && q.containsKey("until_date") && q.containsKey("page")
                        && q.containsKey("page_size");
                String body;
                int code;
                if (!authorized) {
                    code = 401; body = "{\"error\":{\"type\":\"INVALID_ACCESS_TOKEN\"}}";
                } else if (!valid) {
                    code = 422; body = "{\"error\":{\"type\":\"INPUT_VALIDATION_ERROR\"}}";
                } else {
                    var data = TRANSACTIONS.values().stream()
                            .filter(t -> !q.containsKey("status") || t.contains("\"status\":\"" + q.get("status") + "\""))
                            .toList();
                    code = 200;
                    body = "{\"data\":[" + String.join(",", data) + "],\"meta\":{\"page\":" + q.get("page")
                            + ",\"page_size\":" + q.get("page_size") + ",\"total_results\":" + data.size() + "}}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void accepted() throws Exception {
        owner = provision("owner", "OWNER"); fixer = provision("fixer", "FIXER"); manager = provision("manager", "REAL_ESTATE_MANAGER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixer);
        jdbc.update("INSERT INTO fixer_specialties(fixer_user_id,specialty) VALUES (?,'PLUMBING')", fixer);
        property = UUID.randomUUID(); PropertyFixtures.insertPublished(jdbc, property, owner);
        jdbc.update("UPDATE properties SET manager_user_id = ? WHERE id = ?", manager, property);
        request = uuid(json(mvc.perform(post("/requests").with(identity("owner")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + property + "\",\"title\":\"Fuga\",\"description\":\"Reparar tuberia\",\"mediaIds\":[],\"paymentMethod\":\"TRANSFER\"}"))
                .andExpect(status().isCreated()).andReturn()), "requestId");
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", request);
        quotation = uuid(json(mvc.perform(post("/quotations").with(identity("fixer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + request + "\",\"amount\":100000,\"estimatedDays\":2,\"message\":\"Reparacion\"}"))
                .andExpect(status().isCreated()).andReturn()), "id");
        payment = uuid(json(mvc.perform(post("/quotations/" + quotation + "/accept").with(identity("owner")))
                .andExpect(status().isOk()).andReturn()), "paymentId");
    }

    private UUID provision(String name, String role) throws Exception {
        UUID id = uuid(json(mvc.perform(post("/auth/bootstrap").with(identity(name))).andExpect(status().isCreated()).andReturn()), "id");
        if (role.equals("REAL_ESTATE_MANAGER") || role.equals("PLATFORM_ADMIN")) {
            jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
            return id;
        }
        mvc.perform(post("/auth/select-role").with(identity(name)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private String paymentReference() {
        return jdbc.queryForObject("SELECT reference FROM repair_payments WHERE id = ?", String.class, payment);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var snapshot = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("repair_payments", "simulated_payment_attempts", "fixer_earnings", "jobs", "job_status_log", "notifications"))
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return snapshot;
    }

    private static Map<String, String> query(String url) {
        var params = new java.util.LinkedHashMap<String, String>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private RequestPostProcessor identity(String name) {
        return jwt().jwt(t -> t.subject("auth0|wompi-" + name).claim("email", name + "@example.test").claim("name", name));
    }
    private JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString()); }
    private UUID uuid(JsonNode j, String key) { return UUID.fromString(j.get(key).asText()); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
}
