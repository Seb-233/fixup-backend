package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Safety (SAF-01): la conciliación del libro de pagos.
 *
 * <p>Primero se mueve plata por el flujo real (pago TRANSFER aprobado) y se comprueba que todo
 * cuadra. Después se rompe el libro a propósito, escribiendo directo en la base como lo haría un
 * script o una migración equivocada, y se comprueba que la conciliación lo detecta con el
 * identificador exacto de la fila.
 */
abstract class LedgerReconciliationHttpContract {

    /** 450.000 pesos: comisión 45.000, neto 405.000. */
    private static final long AMOUNT = 450_000L;
    private static final long COMMISSION = 45_000L;
    private static final long NET = 405_000L;
    private static final String ADMIN = "auth0|ledger-admin";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    /**
     * Aprobar un pago real usa funciones de PostgreSQL (RLS de deudas del propietario), así que los
     * escenarios con plata solo corren contra PostgreSQL. En H2 igual se ejecutan todas las
     * consultas de la conciliación sobre el libro vacío y la autorización.
     */
    protected boolean movesRealMoney() {
        return false;
    }

    @BeforeEach
    @AfterEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
        TestStorageConfiguration.instance().clear();
    }

    @Test
    void anEmptyLedgerIsConsistent() throws Exception {
        provisionAdmin();

        mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.totals.collected").value(0))
                .andExpect(jsonPath("$.findings", hasSize(0)))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty());
    }

    @Test
    void anApprovedTransferLeavesTheLedgerBalanced() throws Exception {
        provisionAdmin();
        approvedTransfer("balanced");

        mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.totals.collected").value(AMOUNT))
                .andExpect(jsonPath("$.totals.earningsGross").value(AMOUNT))
                .andExpect(jsonPath("$.totals.commission").value(COMMISSION))
                .andExpect(jsonPath("$.totals.net").value(NET))
                .andExpect(jsonPath("$.totals.heldNet").value(NET))
                .andExpect(jsonPath("$.findings", hasSize(0)));
    }

    @Test
    void aMissingEarningIsReportedWithThePaymentThatLostIt() throws Exception {
        provisionAdmin();
        var transfer = approvedTransfer("lost-earning");
        tamperBypassingTriggers("DELETE FROM fixer_earnings WHERE quotation_id = ?", transfer.quotationId());

        mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(false))
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.totals.collected").value(AMOUNT))
                .andExpect(jsonPath("$.totals.earningsGross").value(0))
                .andExpect(jsonPath("$.findings", hasSize(1)))
                .andExpect(jsonPath("$.findings[0].code").value("SERVICE_PAYMENT_WITHOUT_EARNING"))
                .andExpect(jsonPath("$.findings[0].count").value(1))
                .andExpect(jsonPath("$.findings[0].sample", hasItem(transfer.paymentId().toString())));
    }

    @Test
    void aPesoMovedFromCommissionToNetIsReported() throws Exception {
        provisionAdmin();
        var transfer = approvedTransfer("one-peso");
        String movePeso = "UPDATE fixer_earnings SET commission_amount = commission_amount - 1, "
                + "net_amount = net_amount + 1 WHERE quotation_id = ?";
        if (movesRealMoney()) {
            // En PostgreSQL el trigger preserve_earning_and_transition (V42) ya lo impide.
            assertThatThrownBy(() -> jdbc.update(movePeso, transfer.quotationId()))
                    .hasMessageContaining("Earning financial terms are immutable");
        }
        // La suma sigue cuadrando, así que la CHECK de la tabla no lo detiene. Se simula un script
        // con privilegios que salta los triggers: solo la regla de la tarifa lo ve.
        tamperBypassingTriggers(movePeso, transfer.quotationId());
        UUID earningId = jdbc.queryForObject("SELECT id FROM fixer_earnings WHERE quotation_id = ?",
                UUID.class, transfer.quotationId());

        mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(false))
                .andExpect(jsonPath("$.findings[0].code").value("EARNING_COMMISSION_MISMATCH"))
                .andExpect(jsonPath("$.findings[0].sample", hasItem(earningId.toString())));
    }

    @Test
    void onlyAnActivePlatformAdminCanReconcile() throws Exception {
        String owner = "auth0|ledger-owner-forbidden";
        provisionWithRole(owner, "OWNER");

        mvc.perform(get("/administration/payments/reconciliation").with(identity(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/administration/payments/reconciliation"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- fixtures ----------

    private record ApprovedTransfer(UUID paymentId, UUID quotationId) {
    }

    private ApprovedTransfer approvedTransfer(String suffix) throws Exception {
        Assumptions.assumeTrue(movesRealMoney(), "Requires PostgreSQL");
        String owner = "auth0|ledger-owner-" + suffix;
        String fixer = "auth0|ledger-fixer-" + suffix;
        UUID ownerId = provisionWithRole(owner, "OWNER");
        UUID fixerId = provisionWithRole(fixer, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixerId);
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, 'PLUMBING')", fixerId);
        UUID propertyId = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertPublished(jdbc, propertyId, ownerId);

        var request = mvc.perform(post("/requests").with(identity(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId":"%s","title":"Fuga de agua","description":"Reparar tubería principal",
                                 "mediaIds":[],"paymentMethod":"TRANSFER"}
                                """.formatted(propertyId)))
                .andExpect(status().isCreated()).andReturn();
        UUID requestId = UUID.fromString(mapper.readTree(request.getResponse().getContentAsString())
                .get("requestId").asText());
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", requestId);

        var quotation = mvc.perform(post("/quotations").with(identity(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","amount":%d,"estimatedDays":2,"message":"Materiales y mano de obra"}
                                """.formatted(requestId, AMOUNT)))
                .andExpect(status().isCreated()).andReturn();
        UUID quotationId = UUID.fromString(mapper.readTree(quotation.getResponse().getContentAsString())
                .get("id").asText());

        var acceptance = mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(owner)))
                .andExpect(status().isOk()).andReturn();
        UUID paymentId = UUID.fromString(mapper.readTree(acceptance.getResponse().getContentAsString())
                .get("paymentId").asText());
        mvc.perform(post("/payments/" + paymentId + "/simulate").with(identity(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        return new ApprovedTransfer(paymentId, quotationId);
    }

    /**
     * Escribe directo en la base como lo haría un script o una migración con privilegios. En
     * PostgreSQL desactiva los triggers de la sesión (session_replication_role = replica), que es
     * justo el caso que la conciliación existe para atrapar.
     */
    private void tamperBypassingTriggers(String sql, Object... args) {
        if (!movesRealMoney()) {
            jdbc.update(sql, args);
            return;
        }
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var bypass = connection.createStatement()) {
                bypass.execute("SET session_replication_role = replica");
            }
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                statement.executeUpdate();
            } finally {
                try (var restore = connection.createStatement()) {
                    restore.execute("SET session_replication_role = origin");
                }
            }
            return null;
        });
    }

    private void provisionAdmin() throws Exception {
        UUID adminId = provision(ADMIN);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", adminId);
    }

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
}
