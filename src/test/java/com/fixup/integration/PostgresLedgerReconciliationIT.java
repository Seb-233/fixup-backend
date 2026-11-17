package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * El mismo contrato contra PostgreSQL. Aquí sí hay RLS: la conciliación tiene que ver todas las
 * filas aunque el administrador que la pide no pueda leer los ingresos de cada técnico. Además,
 * lo que solo existe en PostgreSQL: foto coherente con pagos entrando, turno entre réplicas y qué
 * pasa cuando el libro no se puede leer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class PostgresLedgerReconciliationIT extends LedgerReconciliationHttpContract {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("fixup.payments.reconciliation.timeout", () -> "PT2S");
    }

    @Override
    protected boolean movesRealMoney() {
        return true;
    }

    private static final String ROOT = "auth0|ledger-root";
    /** El mismo turno que usa JdbcLedgerChecks.REPLICA_TURN_KEY. */
    private static final long REPLICA_TURN_KEY = 0x4649585550534146L;

    /**
     * Mientras otra conexión confirma pagos completos (pago, intento e ingreso en una transacción),
     * cada conciliación tiene que ver una foto coherente: nunca un pago sin su ingreso. Con READ
     * COMMITTED las sumas saldrían de momentos distintos y habría descuadres falsos.
     */
    @Test
    void reconciliationSeesACoherentSnapshotWhilePaymentsCommit() throws Exception {
        admin();
        var ledger = new LedgerFixture(jdbc).seed();
        var stop = new AtomicBoolean();
        var writer = CompletableFuture.supplyAsync(() -> {
            int committed = 0;
            while (!stop.get() && committed < 400) {
                var service = new LedgerFixture.Case();
                ledger.write(db -> {
                    ledger.payment(db, service, ledger.owner1, ledger.fixer1, 10_000, "SERVICE", "APPROVED");
                    ledger.attempt(db, service.payment, service.approvedAttempt, "APPROVED", ledger.owner1);
                    ledger.earning(db, service, ledger.fixer1, 10_000, "HELD", LedgerFixture.now());
                });
                committed++;
            }
            return committed;
        });
        long lastCollected = 0;
        try {
            for (int run = 0; run < 25; run++) {
                var body = mapper.readTree(mvc.perform(get("/administration/payments/reconciliation").with(root()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                assertThat(body.get("consistent").asBoolean()).as(body.toPrettyString()).isTrue();
                long collected = body.get("totals").get("serviceCollected").asLong();
                assertThat(collected).isEqualTo(body.get("totals").get("serviceEarningsGross").asLong())
                        .isGreaterThanOrEqualTo(lastCollected);
                assertThat((collected - LedgerFixture.SERVICE_COLLECTED) % 10_000).isZero();
                lastCollected = collected;
            }
        } finally {
            stop.set(true);
        }
        assertThat(writer.get(30, TimeUnit.SECONDS)).isPositive();
        assertThat(lastCollected).isGreaterThan(LedgerFixture.SERVICE_COLLECTED);
    }

    /**
     * Varias réplicas: mientras una concilia, las demás no repiten el escaneo. Se simula la otra
     * réplica tomando el mismo turno desde otra conexión.
     */
    @Test
    void onlyOneReplicaScansTheLedgerAtATime() throws Exception {
        admin();
        new LedgerFixture(jdbc).seed();
        try (var otherReplica = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            otherReplica.setAutoCommit(false);
            try (var turn = otherReplica.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                turn.setLong(1, REPLICA_TURN_KEY);
                turn.execute();
            }

            mvc.perform(get("/administration/payments/reconciliation").with(root()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "10"))
                    .andExpect(jsonPath("$.code").value("LEDGER_RECONCILIATION_BUSY"))
                    .andExpect(jsonPath("$.consistent").doesNotExist());

            otherReplica.rollback();
        }
        mvc.perform(get("/administration/payments/reconciliation").with(root()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    /**
     * Si el rol lector pierde el permiso sobre una columna del libro, no hay resultado: 503. Antes
     * de este cambio, una lectura que no veía filas respondía 200 con "cuadra".
     */
    @Test
    void aLedgerThatCannotBeReadIsNeverReportedAsConsistent() throws Exception {
        admin();
        new LedgerFixture(jdbc).seed();
        jdbc.execute("REVOKE SELECT (gross_amount) ON fixer_earnings FROM fixup_ledger_auditor");
        try {
            mvc.perform(get("/administration/payments/reconciliation").with(root()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LEDGER_UNREADABLE"))
                    .andExpect(jsonPath("$.consistent").doesNotExist())
                    .andExpect(jsonPath("$.totals").doesNotExist());
        } finally {
            jdbc.execute("GRANT SELECT (gross_amount) ON fixer_earnings TO fixup_ledger_auditor");
        }
        mvc.perform(get("/administration/payments/reconciliation").with(root()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    /**
     * Una corrida que no termina a tiempo (aquí la bloquea otra transacción) no tiene resultado: el
     * statement_timeout la corta y su transacción abortada no deja la conexión con el rol lector ni
     * el turno tomado. La siguiente corrida lee el libro normalmente.
     */
    @Test
    void aRunThatHitsItsTimeLimitHasNoResultAndTheNextOneRecovers() throws Exception {
        admin();
        new LedgerFixture(jdbc).seed();
        try (var otherSession = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            otherSession.setAutoCommit(false);
            try (var lock = otherSession.createStatement()) {
                lock.execute("LOCK TABLE fixer_earnings IN ACCESS EXCLUSIVE MODE");
            }
            long started = System.nanoTime();
            mvc.perform(get("/administration/payments/reconciliation").with(root()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LEDGER_UNREADABLE"))
                    .andExpect(jsonPath("$.consistent").doesNotExist())
                    .andExpect(jsonPath("$.totals").doesNotExist());
            // Límite de 2 s más el margen de 5 s para recibir el error de la transacción.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7));
            otherSession.rollback();
        }
        for (int connection = 0; connection < 5; connection++) {
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(POSTGRES.getUsername());
        }
        mvc.perform(get("/administration/payments/reconciliation").with(root()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    private void admin() throws Exception {
        var created = mvc.perform(post("/auth/bootstrap").with(root())).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", id);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor root() {
        return jwt().jwt(token -> token.subject(ROOT).claim("email", "root@example.test")
                .claim("name", "Synthetic User"));
    }
}
