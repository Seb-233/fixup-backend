package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.contracts.application.ContractExpiryScheduler;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import com.fixup.testsupport.PropertyFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RNF-05 (integridad y registro), RNF-11 (autorizacion) y RNF-12 (aislamiento) de la bitacora de
 * transiciones contra PostgreSQL real.
 *
 * <p>El backend se conecta con privilegios equivalentes al runtime de Green: otro usuario aplica las
 * migraciones y es dueño de las tablas; el de ejecucion no es superusuario, no tiene BYPASSRLS ni DDL
 * y solo es miembro de fixup_app (ver db/green_runtime_roles.sql). Por eso las politicas de V50 se
 * comprueban con los privilegios efectivos del runtime y no con el superusuario del contenedor.
 *
 * <p>Los datos se preparan y se comprueban con una conexion administrativa aparte, y las escrituras
 * bajo prueba se hacen con los mismos pasos que RlsSessionTransactionManager ejecuta para una
 * peticion real (SET LOCAL ROLE fixup_app + variables de sesion) o sin actor, como un job de sistema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class PostgresStatusChangeAuditIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("db/green_runtime_roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "fixup_runtime");
        registry.add("spring.datasource.password", () -> "runtime-it");
        // Las migraciones las aplica otro usuario, como el Job de migracion de Green.
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    /** Conexion del backend (fixup_runtime): solo se usa para demostrar con que privilegios corre. */
    @Autowired JdbcTemplate runtime;
    @Autowired ContractExpiryScheduler contractExpiry;

    /** Conexion administrativa (dueña de las tablas) para preparar datos y comprobar resultados. */
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private IntegrationDatabaseCleaner cleaner;

    @BeforeEach
    void prepare() {
        var admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection(admin), true));
        transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        cleaner = new IntegrationDatabaseCleaner(jdbc, transactions);
        cleaner.clean();
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS audit_it_fail_completion ON repair_requests");
        jdbc.execute("DROP FUNCTION IF EXISTS audit_it_fail_completion()");
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

    // ---------- session helpers: exactly what RlsSessionTransactionManager does for a request ----------

    /** Escribe como fixup_app con el actor de la sesion; revierte al terminar (solo importa el efecto). */
    private <T> T asActor(UUID userId, String role, Supplier<T> action) {
        var template = new TransactionTemplate(transactions);
        return template.execute(tx -> {
            jdbc.execute("SET LOCAL ROLE fixup_app");
            jdbc.queryForObject("SELECT set_config('app.current_user_id', ?, true)", String.class,
                    userId.toString());
            jdbc.queryForObject("SELECT set_config('app.current_user_roles', ?, true)", String.class, role);
            T result = action.get();
            tx.setRollbackOnly();
            return result;
        });
    }

    /** Escribe como fixup_app sin actor: el proceso de confianza del webhook, un job o un fixture. */
    private <T> T asSystem(Supplier<T> action) {
        var template = new TransactionTemplate(transactions);
        return template.execute(tx -> {
            jdbc.execute("SET LOCAL ROLE fixup_app");
            T result = action.get();
            tx.setRollbackOnly();
            return result;
        });
    }

    // ---------- fixtures ----------

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }

    private String subject(String name) {
        return "auth0|audit-" + name;
    }

    private UUID provision(String name, String role) throws Exception {
        UUID id = uuid(mvc.perform(post("/auth/bootstrap").with(identity(subject(name))))
                .andExpect(status().isCreated()).andReturn(), "id");
        if ("PLATFORM_ADMIN".equals(role) || "REAL_ESTATE_MANAGER".equals(role)) {
            // El rol de plataforma no se elige por HTTP: la prueba lo otorga directamente.
            jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
            return id;
        }
        mvc.perform(post("/auth/select-role").with(identity(subject(name)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private void verifyFixer(UUID fixer) throws Exception {
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixer);
        jdbc.update("INSERT INTO fixer_specialties(fixer_user_id, specialty) VALUES (?, 'PLUMBING')", fixer);
    }

    private UUID uuid(MvcResult result, String field) throws Exception {
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get(field).asText());
    }

    /** Un recurso confirmado en produccion: solicitud, cotizacion, pago aprobado y trabajo abierto. */
    private Fixture fundedJob() throws Exception {
        UUID owner = provision("flow-owner", "OWNER");
        UUID fixer = provision("flow-fixer", "FIXER");
        verifyFixer(fixer);
        UUID property = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, property, owner);

        UUID request = uuid(mvc.perform(post("/requests").with(identity(subject("flow-owner")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + property + "\",\"title\":\"Fuga de agua\","
                        + "\"description\":\"Reparar tuberia principal\",\"mediaIds\":[],"
                        + "\"paymentMethod\":\"TRANSFER\"}"))
                .andExpect(status().isCreated()).andReturn(), "requestId");
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", request);

        UUID quotation = uuid(mvc.perform(post("/quotations").with(identity(subject("flow-fixer")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + request + "\",\"amount\":100000,\"estimatedDays\":2,"
                        + "\"message\":\"Materiales incluidos\"}"))
                .andExpect(status().isCreated()).andReturn(), "id");

        UUID payment = uuid(mvc.perform(post("/quotations/" + quotation + "/accept")
                .with(identity(subject("flow-owner")))).andExpect(status().isOk()).andReturn(), "paymentId");

        mvc.perform(post("/payments/" + payment + "/simulate").with(identity(subject("flow-owner")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        UUID job = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class, quotation);
        return new Fixture(owner, fixer, request, quotation, payment, job);
    }

    private record Fixture(UUID owner, UUID fixer, UUID request, UUID quotation, UUID payment, UUID job) { }

    // ---------- trail helpers ----------

    private int entries() {
        return jdbc.queryForObject("SELECT count(*) FROM status_change_log", Integer.class);
    }

    private int entriesFor(String resourceType, UUID resourceId) {
        return jdbc.queryForObject("SELECT count(*) FROM status_change_log WHERE resource_type = ? "
                + "AND resource_id = ?", Integer.class, resourceType, resourceId);
    }

    private Map<String, Object> transitionTo(String resourceType, UUID resourceId, String newStatus) {
        return jdbc.queryForMap("SELECT previous_status, new_status, changed_by_user_id, changed_by_subject "
                + "FROM status_change_log WHERE resource_type = ? AND resource_id = ? AND new_status = ?",
                resourceType, resourceId, newStatus);
    }

    private void insertTrailRow(UUID changedByUserId, String changedBySubject) {
        jdbc.update("INSERT INTO status_change_log(id, resource_type, resource_id, previous_status, "
                        + "new_status, changed_by_user_id, changed_by_subject, changed_at) "
                        + "VALUES (?, 'REPAIR_REQUEST', ?, 'OPEN', 'ASSIGNED', ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), UUID.randomUUID(), changedByUserId, changedBySubject);
    }

    private int visibleEntries(UUID userId, String role) {
        return asActor(userId, role,
                () -> jdbc.queryForObject("SELECT count(*) FROM status_change_log", Integer.class));
    }

    // ---------- RNF-12: el runtime restringido, no el superusuario del contenedor ----------

    @Test
    void theBackendRunsRestrictedAndTheTrailIsForcedThroughRls() {
        insertTrailRow(null, "SYSTEM");

        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("fixup_runtime");
        assertThat(runtime.queryForObject("SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb "
                + "FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'CREATE')",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT tableowner FROM pg_tables WHERE tablename = 'status_change_log'",
                String.class)).isNotIn("fixup_runtime", "fixup_app");

        // FORCE ROW LEVEL SECURITY: ni siquiera el dueño de la tabla queda exento de las politicas.
        assertThat(jdbc.queryForObject("SELECT relforcerowsecurity FROM pg_class "
                + "WHERE relname = 'status_change_log'", Boolean.class)).isTrue();
        // Sin actor no hay lectura general: RLS se aplica de verdad a esta conexion.
        assertThat(runtime.queryForObject("SELECT count(*) FROM status_change_log", Integer.class)).isZero();

        // Append-only: el privilegio de UPDATE/DELETE/TRUNCATE no existe para el rol de la aplicacion.
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('fixup_app', 'status_change_log', 'UPDATE') "
                + "OR has_table_privilege('fixup_app', 'status_change_log', 'DELETE') "
                + "OR has_table_privilege('fixup_app', 'status_change_log', 'TRUNCATE')", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('fixup_app', 'status_change_log', 'SELECT')",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('fixup_app', 'status_change_log', 'INSERT')",
                Boolean.class)).isTrue();
    }

    @Test
    void nobodyCanUpdateOrDeleteTheTrailNotEvenPlatformAdmin() throws Exception {
        UUID admin = provision("tamper-admin", "PLATFORM_ADMIN");
        insertTrailRow(null, "SYSTEM");

        assertThatThrownBy(() -> asActor(admin, "PLATFORM_ADMIN",
                () -> jdbc.update("UPDATE status_change_log SET new_status = 'TAMPERED'")))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asActor(admin, "PLATFORM_ADMIN",
                () -> jdbc.update("DELETE FROM status_change_log")))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> asSystem(() -> jdbc.update("DELETE FROM status_change_log")))
                .hasStackTraceContaining("permission denied");

        assertThat(entries()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT new_status FROM status_change_log", String.class))
                .isEqualTo("ASSIGNED");
    }

    // ---------- RNF-11: autenticidad del registro ----------

    @Test
    void aSessionCanOnlySignItsOwnTransitions() throws Exception {
        UUID owner = provision("sign-owner", "OWNER");
        UUID fixer = provision("sign-fixer", "FIXER");

        // La transicion a nombre de la propia sesion se acepta.
        assertThat(asActor(owner, "OWNER",
                () -> { insertTrailRow(owner, subject("sign-owner")); return 1; })).isEqualTo(1);
        // Atribuirla a otro actor se rechaza.
        assertThatThrownBy(() -> asActor(owner, "OWNER",
                () -> jdbc.update("INSERT INTO status_change_log(id, resource_type, resource_id, "
                                + "previous_status, new_status, changed_by_user_id, changed_by_subject, changed_at) "
                                + "VALUES (?, 'JOB', ?, null, 'IN_PROGRESS', ?, ?, CURRENT_TIMESTAMP)",
                        UUID.randomUUID(), UUID.randomUUID(), fixer, subject("sign-fixer"))))
                .hasStackTraceContaining("row-level security policy");
        // Una sesion autenticada no puede firmar entradas de sistema.
        assertThatThrownBy(() -> asActor(owner, "OWNER",
                () -> jdbc.update("INSERT INTO status_change_log(id, resource_type, resource_id, "
                                + "previous_status, new_status, changed_by_user_id, changed_by_subject, changed_at) "
                                + "VALUES (?, 'REPAIR_REQUEST', ?, 'OPEN', 'SLA_BREACHED', NULL, 'SYSTEM', "
                                + "CURRENT_TIMESTAMP)",
                        UUID.randomUUID(), UUID.randomUUID())))
                .hasStackTraceContaining("row-level security policy");
        // Tampoco sin actor declarado pero con un sujeto que no es de sistema.
        assertThatThrownBy(() -> asActor(owner, "OWNER",
                () -> jdbc.update("INSERT INTO status_change_log(id, resource_type, resource_id, "
                                + "previous_status, new_status, changed_by_user_id, changed_by_subject, changed_at) "
                                + "VALUES (?, 'REPAIR_REQUEST', ?, 'OPEN', 'SLA_BREACHED', NULL, ?, "
                                + "CURRENT_TIMESTAMP)",
                        UUID.randomUUID(), UUID.randomUUID(), subject("sign-owner"))))
                .hasStackTraceContaining("row-level security policy");
        // Y tampoco se pueden fabricar entradas a nombre de otro desde la conexion de la aplicacion.
        assertThatThrownBy(() -> runtime.update("INSERT INTO status_change_log(id, resource_type, "
                        + "resource_id, previous_status, new_status, changed_by_user_id, changed_by_subject, "
                        + "changed_at) VALUES (?, 'JOB', ?, null, 'IN_PROGRESS', ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), UUID.randomUUID(), fixer, subject("sign-fixer")))
                .hasStackTraceContaining("row-level security policy");

        assertThat(entries()).isZero();

        // El proceso de confianza sin actor si registra la transicion de sistema.
        assertThat(asSystem(() -> {
            jdbc.update("INSERT INTO status_change_log(id, resource_type, resource_id, previous_status, "
                            + "new_status, changed_by_user_id, changed_by_subject, changed_at) "
                            + "VALUES (?, 'REPAIR_REQUEST', ?, 'OPEN', 'SLA_BREACHED', NULL, 'SYSTEM', "
                            + "CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), UUID.randomUUID());
            return 1;
        })).isEqualTo(1);
    }

    // ---------- RNF-11: la consulta administrativa es la unica lectura ----------

    @Test
    void onlyPlatformAdminReadsTheTrailAndNoBusinessUserReadsOtherTransitions() throws Exception {
        UUID owner = provision("read-owner", "OWNER");
        UUID fixer = provision("read-fixer", "FIXER");
        UUID manager = provision("read-manager", "REAL_ESTATE_MANAGER");
        UUID admin = provision("read-admin", "PLATFORM_ADMIN");
        insertTrailRow(owner, subject("read-owner"));
        insertTrailRow(fixer, subject("read-fixer"));

        assertThat(visibleEntries(owner, "OWNER")).as("OWNER").isZero();
        assertThat(visibleEntries(fixer, "FIXER")).as("FIXER").isZero();
        assertThat(visibleEntries(manager, "REAL_ESTATE_MANAGER")).as("REAL_ESTATE_MANAGER").isZero();
        assertThat(visibleEntries(admin, "PLATFORM_ADMIN")).as("PLATFORM_ADMIN").isEqualTo(2);
    }

    // ---------- RNF-05: una transicion confirmada, un registro ----------

    @Test
    void everyConfirmedTransitionLeavesExactlyOneEntry() throws Exception {
        UUID owner = provision("count-owner", "OWNER");
        UUID fixer = provision("count-fixer", "FIXER");
        verifyFixer(fixer);
        UUID property = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, property, owner);

        UUID request = uuid(mvc.perform(post("/requests").with(identity(subject("count-owner")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + property + "\",\"title\":\"Fuga\","
                        + "\"description\":\"Reparar tuberia\",\"mediaIds\":[],\"paymentMethod\":\"TRANSFER\"}"))
                .andExpect(status().isCreated()).andReturn(), "requestId");
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", request);
        assertThat(entries()).as("crear la solicitud").isEqualTo(1);
        assertThat(transitionTo("REPAIR_REQUEST", request, "OPEN"))
                .containsEntry("previous_status", null).containsEntry("changed_by_user_id", owner)
                .containsEntry("changed_by_subject", subject("count-owner"));

        UUID quotation = uuid(mvc.perform(post("/quotations").with(identity(subject("count-fixer")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + request + "\",\"amount\":100000,\"estimatedDays\":2,"
                        + "\"message\":\"Cotizacion\"}"))
                .andExpect(status().isCreated()).andReturn(), "id");
        assertThat(entries()).as("cotizar no es una transicion auditada").isEqualTo(1);

        UUID payment = uuid(mvc.perform(post("/quotations/" + quotation + "/accept")
                .with(identity(subject("count-owner")))).andExpect(status().isOk()).andReturn(), "paymentId");
        assertThat(entries()).as("asignar la solicitud y abrir el pago").isEqualTo(3);
        assertThat(transitionTo("REPAIR_REQUEST", request, "ASSIGNED"))
                .containsEntry("previous_status", "OPEN").containsEntry("changed_by_user_id", owner);
        assertThat(transitionTo("REPAIR_PAYMENT", payment, "PENDING"))
                .containsEntry("previous_status", null).containsEntry("changed_by_user_id", owner);

        mvc.perform(post("/payments/" + payment + "/simulate").with(identity(subject("count-owner")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        UUID job = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class, quotation);
        assertThat(entries()).as("aprobar el pago y abrir el trabajo").isEqualTo(5);
        assertThat(transitionTo("REPAIR_PAYMENT", payment, "APPROVED"))
                .containsEntry("previous_status", "PENDING").containsEntry("changed_by_user_id", owner);
        // El trabajo nace en ASSIGNED al aprobarse el pago (FR-UC-19), no en IN_PROGRESS.
        assertThat(transitionTo("JOB", job, "ASSIGNED"))
                .containsEntry("previous_status", null).containsEntry("changed_by_user_id", owner);

        mvc.perform(post("/jobs/" + job + "/complete").with(identity(subject("count-fixer"))))
                .andExpect(status().isOk());
        assertThat(entries()).as("cerrar el trabajo y completar la solicitud").isEqualTo(7);
        assertThat(transitionTo("JOB", job, "COMPLETED"))
                .containsEntry("changed_by_user_id", fixer).containsEntry("changed_by_subject", subject("count-fixer"));
        assertThat(transitionTo("REPAIR_REQUEST", request, "COMPLETED"))
                .containsEntry("changed_by_user_id", fixer);

        // Ni una transicion de sistema: todo el recorrido lo firmo quien lo provoco.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_change_log WHERE changed_by_user_id IS NULL",
                Integer.class)).isZero();
        // Y el numero de transiciones distintas coincide con el numero de filas: no hay duplicados.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT DISTINCT resource_type, resource_id, "
                + "previous_status, new_status FROM status_change_log) t", Integer.class)).isEqualTo(entries());
    }

    @Test
    void aRolledBackTransitionLeavesNoEntry() throws Exception {
        Fixture fixture = fundedJob();
        int before = entries();

        // Falla la escritura de negocio (la solicitud) despues de que la bitacora ya inserto su fila.
        jdbc.execute("""
                CREATE FUNCTION audit_it_fail_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'AUDIT_IT_INJECTED_FAILURE';
                END $$
                """);
        jdbc.execute("CREATE TRIGGER audit_it_fail_completion AFTER UPDATE ON repair_requests "
                + "FOR EACH ROW EXECUTE FUNCTION audit_it_fail_completion()");

        assertThatThrownBy(() -> mvc.perform(post("/jobs/" + fixture.job() + "/complete")
                .with(identity(subject("flow-fixer"))))).hasStackTraceContaining("AUDIT_IT_INJECTED_FAILURE");

        assertThat(entries()).isEqualTo(before);
        assertThat(entriesFor("JOB", fixture.job())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_change_log WHERE new_status = 'COMPLETED'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, fixture.job()))
                .isNotEqualTo("COMPLETED");
    }

    @Test
    void retryingAConfirmedTransitionDoesNotDuplicateTheEntry() throws Exception {
        Fixture fixture = fundedJob();
        mvc.perform(post("/jobs/" + fixture.job() + "/complete").with(identity(subject("flow-fixer"))))
                .andExpect(status().isOk());
        int confirmed = entries();

        mvc.perform(post("/jobs/" + fixture.job() + "/complete").with(identity(subject("flow-fixer"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_ALREADY_COMPLETED"));
        mvc.perform(post("/jobs/" + fixture.job() + "/complete").with(identity(subject("flow-fixer"))))
                .andExpect(status().isConflict());

        assertThat(entries()).isEqualTo(confirmed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_change_log WHERE resource_type = 'JOB' "
                + "AND resource_id = ? AND new_status = 'COMPLETED'", Integer.class, fixture.job())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_change_log WHERE resource_type = "
                + "'REPAIR_REQUEST' AND resource_id = ? AND new_status = 'COMPLETED'", Integer.class,
                fixture.request())).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsOfTheSameTransitionAreRecordedOnce() throws Exception {
        Fixture fixture = fundedJob();
        int before = entries();

        var startGate = new CountDownLatch(1);
        var doneGate = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        Callable<Integer> task = () -> {
            startGate.await(5, TimeUnit.SECONDS);
            try {
                return mvc.perform(post("/jobs/" + fixture.job() + "/complete")
                                .with(identity(subject("flow-fixer"))))
                        .andReturn().getResponse().getStatus();
            } finally {
                doneGate.countDown();
            }
        };
        List<Future<Integer>> futures = new ArrayList<>(List.of(pool.submit(task), pool.submit(task)));
        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        assertThat(statuses.stream().filter(code -> code >= 200 && code < 300)).as("un solo ganador").hasSize(1);
        assertThat(statuses.stream().filter(code -> code >= 400)).as("el otro confirma contra un estado ya resuelto")
                .hasSize(1);

        assertThat(entries() - before).as("el trabajo y la solicitud, una vez cada uno").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_change_log WHERE resource_type = 'JOB' "
                + "AND resource_id = ? AND new_status = 'COMPLETED'", Integer.class, fixture.job())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_change_log WHERE resource_type = "
                + "'REPAIR_REQUEST' AND resource_id = ? AND new_status = 'COMPLETED'", Integer.class,
                fixture.request())).isEqualTo(1);
    }

    @Test
    void aScheduledSystemTransitionIsRecordedOnceAndAsSystem() throws Exception {
        UUID owner = provision("sla-owner", "OWNER");
        UUID tenant = provision("sla-tenant", "TENANT");
        UUID property = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, property, owner);
        UUID contract = UUID.randomUUID();
        jdbc.update("INSERT INTO rental_contracts(id, property_id, owner_user_id, tenant_user_id, "
                        + "start_date, end_date, monthly_rent_amount, deposit_amount, payment_day_of_month, "
                        + "status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1000000, 0, 5, 'ACTIVE', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                contract, property, owner, tenant, LocalDate.now().minusMonths(6), LocalDate.now().minusDays(1));

        // El job no publica actor: es la unica via por la que se admite una transicion de sistema.
        contractExpiry.processDay(LocalDate.now());
        contractExpiry.processDay(LocalDate.now());

        assertThat(entriesFor("RENTAL_CONTRACT", contract)).isEqualTo(1);
        assertThat(transitionTo("RENTAL_CONTRACT", contract, "EXPIRED"))
                .containsEntry("previous_status", "ACTIVE").containsEntry("changed_by_user_id", null)
                .containsEntry("changed_by_subject", "SYSTEM");
        assertThat(jdbc.queryForObject("SELECT status FROM rental_contracts WHERE id = ?", String.class, contract))
                .isEqualTo("EXPIRED");
    }

    // ---------- RNF-11: contrato HTTP de la consulta ----------

    @Test
    void theAdminEndpointEnforcesAuthenticationRoleFiltersAndLimits() throws Exception {
        Fixture fixture = fundedJob();
        provision("query-admin", "PLATFORM_ADMIN");
        String admin = subject("query-admin");

        mvc.perform(get("/administration/audit/status-changes")).andExpect(status().isUnauthorized());

        mvc.perform(get("/administration/audit/status-changes").with(identity(subject("flow-owner"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/administration/audit/status-changes").with(identity(subject("flow-fixer"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/administration/audit/status-changes")
                        .param("resourceId", fixture.request().toString()).with(identity(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        var everything = mvc.perform(get("/administration/audit/status-changes").with(identity(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(everything.getResponse().getContentAsString())).hasSize(entries());

        var filtered = mvc.perform(get("/administration/audit/status-changes")
                        .param("resourceType", "REPAIR_REQUEST")
                        .param("resourceId", fixture.request().toString()).with(identity(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(filtered.getResponse().getContentAsString())).hasSize(2);
        assertThat(filtered.getResponse().getContentAsString()).contains("\"resourceType\":\"REPAIR_REQUEST\"");

        var clampedDown = mvc.perform(get("/administration/audit/status-changes").param("limit", "0")
                .with(identity(admin))).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(clampedDown.getResponse().getContentAsString())).hasSize(1);

        var clampedUp = mvc.perform(get("/administration/audit/status-changes").param("limit", "9999")
                .with(identity(admin))).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(clampedUp.getResponse().getContentAsString())).hasSize(entries());
    }
}
