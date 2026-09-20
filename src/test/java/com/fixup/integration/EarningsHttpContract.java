package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-UC-20: contrato HTTP del dinero, recorrido de extremo a extremo contra la base.
 *
 * <p>Las pruebas de dominio ya cubren la aritmética y las transiciones. Lo que falta verificar es
 * que el recorrido completo funcione por la API: aceptar la cotización retiene el dinero, cerrar
 * el trabajo lo libera, y la transferencia se lleva exactamente el saldo disponible. Se reutiliza
 * sin cambios contra H2 y contra PostgreSQL.
 */
abstract class EarningsHttpContract {

    /** 450.000 pesos: comisión 45.000, neto 405.000. Números redondos para leer los fallos. */
    private static final long AMOUNT = 450_000L;
    private static final long COMMISSION = 45_000L;
    private static final long NET = 405_000L;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    /**
     * También después de cada prueba: este contrato crea solicitudes y cotizaciones, y otras
     * clases que corren más tarde limpian con su propio orden de borrado, anterior al módulo
     * `requests`. Dejar la base vacía es lo que evita que hereden filas que no saben borrar.
     */
    @BeforeEach
    @AfterEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
        TestStorageConfiguration.instance().clear();
    }

    // ---------- helpers ----------

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }

    private UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID provisionWithRole(String subject, String role) throws Exception {
        UUID id = provision(subject);
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"%s\"}".formatted(role)))
                .andExpect(status().isOk());
        return id;
    }

    private UUID provisionVerifiedFixer(String subject, String specialty) throws Exception {
        UUID fixerId = provisionWithRole(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixerId);
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, ?)",
                fixerId, specialty);
        return fixerId;
    }

    private UUID createRequest(String ownerSubject, String specialty) throws Exception {
        String body = """
                {
                    "specialty": "%s",
                    "title": "Tubo roto",
                    "description": "Fuga constante de agua en la cocina",
                    "mediaIds": []
                }
                """.formatted(specialty);
        var result = mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .get("requestId").asText());
    }

    private UUID submitQuotation(String fixerSubject, UUID requestId, long amount) throws Exception {
        String body = """
                {
                    "requestId": "%s",
                    "amount": %d,
                    "estimatedDays": 3,
                    "message": "Cambio del tramo dañado y prueba de presión"
                }
                """.formatted(requestId, amount);
        var result = mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private void acceptQuotation(String ownerSubject, UUID quotationId) throws Exception {
        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerSubject)))
                .andExpect(status().isOk());
    }

    /** Deja al técnico con un trabajo asignado y su ingreso retenido. Devuelve el id del trabajo. */
    private UUID assignedJob(String ownerSubject, String fixerSubject, long amount) throws Exception {
        UUID requestId = createRequest(ownerSubject, "PLUMBING");
        UUID quotationId = submitQuotation(fixerSubject, requestId, amount);
        acceptQuotation(ownerSubject, quotationId);

        var result = mvc.perform(get("/jobs/me").with(identity(fixerSubject)))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .get(0).get("id").asText());
    }

    // ---------- Tests ----------

    @Test
    void acceptingTheQuotationOpensTheJobAndHoldsTheMoneyInEscrow() throws Exception {
        String owner = "auth0|owner-escrow";
        String fixer = "auth0|fixer-escrow";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");

        UUID jobId = assignedJob(owner, fixer, AMOUNT);

        mvc.perform(get("/jobs/me").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(jobId.toString()))
                .andExpect(jsonPath("$[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$[0].completedAt").doesNotExist());

        // El dinero está comprometido, no disponible: nada que transferir todavía.
        mvc.perform(get("/payments/me/earnings").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(0))
                .andExpect(jsonPath("$.heldBalance").value(NET))
                .andExpect(jsonPath("$.totalCommission").value(COMMISSION))
                .andExpect(jsonPath("$.grossTotal").value(AMOUNT))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].status").value("HELD"))
                .andExpect(jsonPath("$.history[0].grossAmount").value(AMOUNT))
                .andExpect(jsonPath("$.history[0].commissionAmount").value(COMMISSION))
                .andExpect(jsonPath("$.history[0].netAmount").value(NET))
                .andExpect(jsonPath("$.history[0].commissionRateBasisPoints").value(1000))
                .andExpect(jsonPath("$.history[0].releasedAt").doesNotExist());
    }

    @Test
    void closingTheJobReleasesTheMoneyAndTheTransferTakesTheWholeBalance() throws Exception {
        String owner = "auth0|owner-full-walk";
        String fixer = "auth0|fixer-full-walk";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");
        UUID jobId = assignedJob(owner, fixer, AMOUNT);

        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        mvc.perform(get("/payments/me/earnings").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(NET))
                .andExpect(jsonPath("$.heldBalance").value(0))
                .andExpect(jsonPath("$.paidOutTotal").value(0))
                .andExpect(jsonPath("$.history[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.history[0].releasedAt").isNotEmpty())
                .andExpect(jsonPath("$.history[0].paidOutAt").doesNotExist())
                // Liberar no vuelve a tarifar: los montos son los mismos de la retención.
                .andExpect(jsonPath("$.history[0].netAmount").value(NET))
                .andExpect(jsonPath("$.history[0].commissionAmount").value(COMMISSION));

        mvc.perform(post("/payments/me/payouts").with(identity(fixer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(NET))
                .andExpect(jsonPath("$.earningCount").value(1))
                .andExpect(jsonPath("$.status").value("REQUESTED"));

        mvc.perform(get("/payments/me/earnings").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(0))
                .andExpect(jsonPath("$.paidOutTotal").value(NET))
                .andExpect(jsonPath("$.history[0].status").value("PAID_OUT"))
                // El historial dice cuándo se transfirió, no solo que se transfirió.
                .andExpect(jsonPath("$.history[0].paidOutAt").isNotEmpty());

        mvc.perform(get("/payments/me/payouts").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].amount").value(NET));

        // El saldo ya se consumió: pedir otra transferencia no registra una vacía.
        mvc.perform(post("/payments/me/payouts").with(identity(fixer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_AVAILABLE_BALANCE"));
    }

    @Test
    void theClientNeverNamesTheAmountToTransfer() throws Exception {
        String owner = "auth0|owner-amount";
        String fixer = "auth0|fixer-amount";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");
        UUID jobId = assignedJob(owner, fixer, AMOUNT);
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(fixer)))
                .andExpect(status().isOk());

        // Un monto en el cuerpo se rechaza de frente. Aceptarlo en silencio y transferir otra
        // cifra dejaría a quien llama creyendo que decidió algo que nunca se leyó.
        mvc.perform(post("/payments/me/payouts").with(identity(fixer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 999999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Y el saldo sigue intacto: el rechazo no consumió nada.
        mvc.perform(post("/payments/me/payouts").with(identity(fixer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(NET));
    }

    @Test
    void severalClosedJobsAreTransferredTogetherAndTheRemainderStaysWithTheFixer() throws Exception {
        String owner = "auth0|owner-many";
        String fixer = "auth0|fixer-many";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");

        // 999 pesos: el 10% son 99,9 y la división entera deja el peso suelto del lado del técnico.
        for (long amount : new long[]{AMOUNT, 999L}) {
            UUID jobId = assignedJob(owner, fixer, amount);
            mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(fixer)))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/payments/me/earnings").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(NET + 900L))
                .andExpect(jsonPath("$.totalCommission").value(COMMISSION + 99L))
                .andExpect(jsonPath("$.grossTotal").value(AMOUNT + 999L));

        mvc.perform(post("/payments/me/payouts").with(identity(fixer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(NET + 900L))
                .andExpect(jsonPath("$.earningCount").value(2));
    }

    @Test
    void onlyTheAssignedFixerClosesTheJob() throws Exception {
        String owner = "auth0|owner-ownership";
        String fixer = "auth0|fixer-ownership";
        String stranger = "auth0|fixer-stranger";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");
        provisionVerifiedFixer(stranger, "PLUMBING");
        UUID jobId = assignedJob(owner, fixer, AMOUNT);

        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Y el dinero sigue retenido: un tercero no puede liberarlo.
        mvc.perform(get("/payments/me/earnings").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldBalance").value(NET))
                .andExpect(jsonPath("$.availableBalance").value(0));
    }

    @Test
    void theSameJobCannotBeClosedTwice() throws Exception {
        String owner = "auth0|owner-twice";
        String fixer = "auth0|fixer-twice";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");
        UUID jobId = assignedJob(owner, fixer, AMOUNT);

        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(fixer)))
                .andExpect(status().isOk());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(fixer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_COMPLETED"));

        // El segundo cierre no liberó el dinero por segunda vez.
        mvc.perform(get("/payments/me/earnings").with(identity(fixer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(NET))
                .andExpect(jsonPath("$.history", hasSize(1)));
    }

    @Test
    void anOwnerSeesNeitherJobsNorBalance() throws Exception {
        String owner = "auth0|owner-no-money";
        String fixer = "auth0|fixer-no-money";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");
        assignedJob(owner, fixer, AMOUNT);

        for (String path : List.of("/jobs/me", "/payments/me/earnings", "/payments/me/payouts")) {
            mvc.perform(get(path).with(identity(owner)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    @Test
    void theMoneyRoutesRejectAnonymousCallers() throws Exception {
        for (String path : List.of("/jobs/me", "/payments/me/earnings", "/payments/me/payouts")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/payments/me/payouts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/jobs/" + UUID.randomUUID() + "/complete")).andExpect(status().isUnauthorized());
    }

    @Test
    void twoSimultaneousTransfersCannotTakeTheSameMoney() throws Exception {
        String owner = "auth0|owner-race";
        String fixer = "auth0|fixer-race";
        provisionWithRole(owner, "OWNER");
        provisionVerifiedFixer(fixer, "PLUMBING");
        UUID jobId = assignedJob(owner, fixer, AMOUNT);
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(fixer)))
                .andExpect(status().isOk());

        var start = new CountDownLatch(1);
        var created = new AtomicInteger();
        var rejected = new AtomicInteger();
        Callable<Void> attempt = () -> {
            start.await();
            int code = mvc.perform(post("/payments/me/payouts").with(identity(fixer)))
                    .andReturn().getResponse().getStatus();
            if (code == 201) {
                created.incrementAndGet();
            } else if (code == 409) {
                rejected.incrementAndGet();
            }
            return null;
        };

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }

        // El saldo es uno solo: exactamente una solicitud se lo lleva y la otra no encuentra nada.
        assertThat(created.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);

        Long transferred = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payouts", Long.class);
        assertThat(transferred).isEqualTo(NET);
    }
}
