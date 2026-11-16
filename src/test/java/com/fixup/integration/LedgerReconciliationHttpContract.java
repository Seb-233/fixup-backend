package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Las nueve reglas y las ecuaciones por tipo de operación se prueban sobre el libro
 * representativo de {@link LedgerFixture}: primero cuadra completo (sin falsos positivos) y luego
 * cada prueba introduce un solo descuadre y exige exactamente la regla que le corresponde.
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

    // ---------- libro representativo: sin falsos positivos ----------

    @Test
    void aRepresentativeLedgerWithServiceAndDebtPaymentsIsConsistent() throws Exception {
        provisionAdmin();
        new LedgerFixture(jdbc).seed();

        JsonNode report = reconcile();

        assertThat(report.get("consistent").asBoolean()).as(report.toPrettyString()).isTrue();
        assertThat(report.get("balanced").asBoolean()).isTrue();
        assertThat(report.get("findings")).isEmpty();
        assertThat(report.get("balances")).hasSize(10).allSatisfy(balance -> {
            assertThat(balance.get("holds").asBoolean()).as(balance.toString()).isTrue();
            assertThat(balance.get("expected").asLong()).isEqualTo(balance.get("actual").asLong());
        });
        JsonNode totals = report.get("totals");
        // El pago de saldo pendiente no se mezcla con los servicios: cada uno contra su propio ingreso.
        assertThat(totals.get("serviceCollected").asLong()).isEqualTo(LedgerFixture.SERVICE_COLLECTED);
        assertThat(totals.get("serviceEarningsGross").asLong()).isEqualTo(LedgerFixture.SERVICE_COLLECTED);
        assertThat(totals.get("debtCollected").asLong()).isEqualTo(LedgerFixture.DEBT_COLLECTED);
        assertThat(totals.get("debtEarningsGross").asLong()).isEqualTo(LedgerFixture.DEBT_COLLECTED);
        assertThat(totals.get("paidDebts").asLong()).isEqualTo(LedgerFixture.DEBT_COLLECTED);
        assertThat(totals.get("collected").asLong())
                .isEqualTo(LedgerFixture.SERVICE_COLLECTED + LedgerFixture.DEBT_COLLECTED);
        // Lo anterior al libro digital se ve aparte y no descuadra.
        assertThat(totals.get("legacyEarningsGross").asLong()).isEqualTo(LedgerFixture.LEGACY_GROSS);
        assertThat(totals.get("legacyPayouts").asLong()).isEqualTo(LedgerFixture.LEGACY_PAYOUT);
        assertThat(totals.get("legacyPaidOutNet").asLong()).isEqualTo(LedgerFixture.LEGACY_PAYOUT);
        assertThat(totals.get("earningsGross").asLong()).isEqualTo(LedgerFixture.EARNINGS_GROSS);
        assertThat(totals.get("commission").asLong()).isEqualTo(LedgerFixture.COMMISSION);
        assertThat(totals.get("net").asLong()).isEqualTo(LedgerFixture.EARNINGS_GROSS - LedgerFixture.COMMISSION);
        assertThat(totals.get("heldNet").asLong()).isEqualTo(LedgerFixture.HELD_NET);
        assertThat(totals.get("availableNet").asLong()).isEqualTo(LedgerFixture.AVAILABLE_NET);
        assertThat(totals.get("paidOutNet").asLong())
                .isEqualTo(LedgerFixture.COMPOSED_PAYOUT + LedgerFixture.LEGACY_PAYOUT);
        assertThat(totals.get("composedPayouts").asLong()).isEqualTo(LedgerFixture.COMPOSED_PAYOUT);
        assertThat(totals.get("payoutEntriesNet").asLong()).isEqualTo(LedgerFixture.COMPOSED_PAYOUT);
        // Efectivo histórico: lo recibido en mano no es plata cobrada por la plataforma.
        assertThat(totals.get("cashAgreed").asLong()).isEqualTo(1_050_000L);
        assertThat(totals.get("cashReceived").asLong()).isEqualTo(700_000L);
        assertThat(totals.get("cashOutstanding").asLong()).isEqualTo(350_000L);
        assertThat(totals.get("outstandingDebts").asLong()).isEqualTo(150_000L);
        // "Sin hallazgos" sobre un libro que sí se leyó completo, con filas de varios dueños.
        JsonNode scanned = report.get("scanned");
        assertThat(scanned.get("payments").asLong()).isEqualTo(7);
        assertThat(scanned.get("paymentAttempts").asLong()).isEqualTo(8);
        assertThat(scanned.get("earnings").asLong()).isEqualTo(6);
        assertThat(scanned.get("payouts").asLong()).isEqualTo(2);
        assertThat(scanned.get("payoutEntries").asLong()).isEqualTo(2);
        assertThat(scanned.get("debts").asLong()).isEqualTo(2);
        assertThat(scanned.get("cashSettlements").asLong()).isEqualTo(3);
    }

    // ---------- una anomalía real por cada una de las nueve reglas ----------

    @Test
    void rule1AServicePaymentThatLostItsEarning() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("DELETE FROM fixer_earnings WHERE id = ?", ledger.heldService.earning);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("SERVICE_PAYMENT_WITHOUT_EARNING", ledger.heldService.payment));
        assertUnbalanced(report, "SERVICE_COLLECTED_EQUALS_SERVICE_EARNINGS");
    }

    @Test
    void rule2AnEarningWithNoPaymentBehindIt() throws Exception {
        var ledger = seededLedger();
        var invented = new LedgerFixture.Case();
        ledger.write(db -> ledger.earning(db, invented, ledger.fixer1, 70_000, "AVAILABLE", LedgerFixture.now()));

        JsonNode report = reconcile();

        assertBroken(report, Map.of("EARNING_WITHOUT_APPROVED_PAYMENT", invented.earning));
        assertUnbalanced(report, "EVERY_EARNING_HAS_ONE_ORIGIN");
    }

    @Test
    void rule3APesoMovedFromCommissionToNet() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("UPDATE fixer_earnings SET commission_amount = commission_amount - 1, "
                + "net_amount = net_amount + 1 WHERE id = ?", ledger.availableService.earning);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("EARNING_COMMISSION_MISMATCH", ledger.availableService.earning));
        // Las sumas siguen cuadrando: solo la regla fila a fila lo ve.
        assertUnbalanced(report);
    }

    @Test
    void rule4APayoutThatSaysMoreThanItsEntries() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("UPDATE payouts SET amount = amount + 1000 WHERE id = ?", ledger.composedPayout);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("PAYOUT_TOTAL_MISMATCH", ledger.composedPayout));
        assertUnbalanced(report, "PAYOUTS_EQUAL_THEIR_ENTRIES");
    }

    @Test
    void rule5AnEarningInAPayoutThatIsAvailableAgain() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("UPDATE fixer_earnings SET status = 'AVAILABLE', paid_out_at = NULL WHERE id = ?",
                ledger.paidOutService.earning);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("PAYOUT_ENTRY_MISMATCH", ledger.paidOutService.earning));
        assertUnbalanced(report, "PAYOUT_ENTRIES_EQUAL_PAID_OUT_EARNINGS");
    }

    @Test
    void rule5AnEarningMarkedAsPaidOutWithoutAnyPayout() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("UPDATE fixer_earnings SET status = 'PAID_OUT', paid_out_at = ? WHERE id = ?",
                LedgerFixture.now(), ledger.secondAvailableService.earning);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("PAYOUT_ENTRY_MISMATCH", ledger.secondAvailableService.earning));
        // No hay ecuación de sumas para esto: por eso existe la regla fila a fila.
        assertUnbalanced(report);
    }

    @Test
    void rule6ADebtMarkedAsPaidWhoseOnlyPaymentWasDeclined() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("UPDATE owner_debts SET status = 'PAID', settled_at = ? WHERE id = ?", LedgerFixture.now(),
                ledger.pendingDebt.debt);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("PAID_DEBT_WITHOUT_PAYMENT", ledger.pendingDebt.debt));
        assertUnbalanced(report, "DEBT_COLLECTED_EQUALS_PAID_DEBTS");
    }

    @Test
    void rule7AnApprovedDebtPaymentThatSettledNothing() throws Exception {
        var ledger = seededLedger();
        ledger.write(db -> {
            db.update("UPDATE repair_payments SET status = 'APPROVED', completed_at = ? WHERE id = ?",
                    LedgerFixture.now(), ledger.pendingDebt.payment);
            ledger.attempt(db, ledger.pendingDebt.payment, UUID.randomUUID(), "APPROVED", ledger.owner2);
        });

        JsonNode report = reconcile();

        assertBroken(report, Map.of("DEBT_PAYMENT_NOT_APPLIED", ledger.pendingDebt.payment));
        assertUnbalanced(report, "DEBT_COLLECTED_EQUALS_DEBT_EARNINGS", "DEBT_COLLECTED_EQUALS_PAID_DEBTS");
    }

    @Test
    void rule8ACashSettlementWhoseDebtChangedAmount() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("UPDATE owner_debts SET amount = 140000 WHERE id = ?", ledger.pendingDebt.debt);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("CASH_SETTLEMENT_DEBT_MISMATCH", ledger.pendingDebt.settlement));
        assertUnbalanced(report, "CASH_OUTSTANDING_EQUALS_DEBTS");
    }

    @Test
    void rule9AnApprovedPaymentWithNoApprovedAttempt() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("DELETE FROM simulated_payment_attempts WHERE id = ?",
                ledger.secondAvailableService.approvedAttempt);

        JsonNode report = reconcile();

        assertBroken(report, Map.of("APPROVED_PAYMENT_WITHOUT_APPROVED_ATTEMPT",
                ledger.secondAvailableService.payment));
        assertUnbalanced(report);
    }

    /**
     * El caso que ninguna regla fila a fila ve: un pago de saldo aprobado sobre una cotización que ya
     * se cobró como servicio, apoyado en el mismo ingreso. Todas las filas tienen pareja; lo que no
     * cuadra es que el mismo ingreso respalde dos cobros.
     */
    @Test
    void theSameEarningCannotBackAServiceAndADebtPayment() throws Exception {
        var ledger = seededLedger();
        var twin = ledger.availableService;
        ledger.write(db -> {
            db.update("""
                    INSERT INTO repair_payments (id, quotation_id, request_id, owner_user_id, fixer_user_id, amount,
                        payment_method, purpose, status, created_at, updated_at, completed_at, reference)
                    SELECT ?, quotation_id, request_id, owner_user_id, fixer_user_id, amount, payment_method,
                        'OUTSTANDING_BALANCE', status, created_at, updated_at, completed_at, ?
                    FROM repair_payments WHERE id = ?
                    """, twin.debt, "FXR-" + twin.debt.toString().replace("-", "").toUpperCase(), twin.payment);
            ledger.attempt(db, twin.debt, UUID.randomUUID(), "APPROVED", ledger.owner2);
            ledger.cashSettlement(db, twin, ledger.owner2, ledger.fixer1, 1_200_000, 0);
            ledger.debt(db, twin, ledger.owner2, ledger.fixer1, 1_200_000, "PAID");
        });

        JsonNode report = reconcile();

        assertThat(report.get("consistent").asBoolean()).isFalse();
        assertThat(report.get("findings")).isEmpty();
        assertUnbalanced(report, "EVERY_EARNING_HAS_ONE_ORIGIN");
    }

    private LedgerFixture seededLedger() throws Exception {
        provisionAdmin();
        return new LedgerFixture(jdbc).seed();
    }

    private JsonNode reconcile() throws Exception {
        var result = mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** Exactamente estas reglas, cada una con una sola fila y su identificador. */
    private static void assertBroken(JsonNode report, Map<String, UUID> expected) {
        assertThat(report.get("consistent").asBoolean()).as(report.toPrettyString()).isFalse();
        List<String> codes = new ArrayList<>();
        for (JsonNode finding : report.get("findings")) {
            String code = finding.get("code").asText();
            codes.add(code);
            assertThat(expected).as(report.get("findings").toPrettyString()).containsKey(code);
            assertThat(finding.get("count").asLong()).as(code).isEqualTo(1);
            assertThat(finding.get("sample")).as(code).hasSize(1);
            assertThat(finding.get("sample").get(0).asText()).as(code).isEqualTo(expected.get(code).toString());
            assertThat(finding.get("description").asText()).isNotBlank();
        }
        assertThat(codes).containsExactlyInAnyOrderElementsOf(expected.keySet());
    }

    /** Exactamente estas ecuaciones dejan de cuadrar; las demás siguen cuadrando. */
    private static void assertUnbalanced(JsonNode report, String... equations) {
        List<String> broken = new ArrayList<>();
        for (JsonNode balance : report.get("balances")) {
            if (!balance.get("holds").asBoolean()) {
                broken.add(balance.get("code").asText());
                assertThat(balance.get("expected").asLong()).isNotEqualTo(balance.get("actual").asLong());
            }
        }
        assertThat(broken).as(report.get("balances").toPrettyString()).containsExactlyInAnyOrder(equations);
        assertThat(report.get("balanced").asBoolean()).isEqualTo(equations.length == 0);
    }

    @Test
    void onlyAnActivePlatformAdminCanReconcile() throws Exception {
        provisionAdmin();
        String owner = "auth0|ledger-owner-forbidden";
        String fixer = "auth0|ledger-fixer-forbidden";
        provisionWithRole(owner, "OWNER");
        provisionWithRole(fixer, "FIXER");

        mvc.perform(get("/administration/payments/reconciliation"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/administration/payments/reconciliation").with(identity(owner)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.totals").doesNotExist());
        mvc.perform(get("/administration/payments/reconciliation").with(identity(fixer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isOk());

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE auth0_subject = ?", ADMIN);
        mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isForbidden());
    }

    /** Solo sumas, conteos e identificadores de fila: nada que identifique a una persona. */
    @Test
    void theReportExposesNoPersonalData() throws Exception {
        var ledger = seededLedger();
        ledger.tamper("DELETE FROM fixer_earnings WHERE id = ?", ledger.heldService.earning);

        String body = reconcile().toString();

        assertThat(body).doesNotContain(ledger.owner1.toString(), ledger.owner2.toString(), ledger.owner3.toString(),
                ledger.fixer1.toString(), ledger.fixer2.toString(), "FXR-", "@", "auth0|");
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
     * Escribe directo en la base como lo haría un script o una migración con privilegios, saltando
     * triggers y llaves foráneas: justo el caso que la conciliación existe para atrapar.
     */
    private void tamperBypassingTriggers(String sql, Object... args) {
        new LedgerFixture(jdbc).tamper(sql, args);
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
