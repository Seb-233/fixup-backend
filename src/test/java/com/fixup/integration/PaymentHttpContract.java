package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for the SERVICE/TRANSFER payment simulator, shared by H2 and PostgreSQL. */
abstract class PaymentHttpContract {
    private static final long AMOUNT = 450_000L;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        SecurityContextHolder.clearContext();
        cleaner.clean();
        TestStorageConfiguration.instance().clear();
    }

    protected boolean supportsRealConcurrency() {
        return false;
    }

    @Test
    void ownerFixerAndAdminCanReadButAnUnrelatedOwnerCannot() throws Exception {
        var transfer = acceptedTransfer("read");
        String otherOwner = "auth0|payment-other-read";
        provisionWithRole(otherOwner, "OWNER");
        String admin = "auth0|payment-admin-read";
        UUID adminId = provision(admin);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", adminId);

        mvc.perform(get("/payments/" + transfer.paymentId()).with(identity(transfer.ownerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(transfer.paymentId().toString()))
                .andExpect(jsonPath("$.quotationId").value(transfer.quotationId().toString()))
                .andExpect(jsonPath("$.requestId").value(transfer.requestId().toString()))
                .andExpect(jsonPath("$.amount").value(AMOUNT))
                .andExpect(jsonPath("$.paymentMethod").value("TRANSFER"))
                .andExpect(jsonPath("$.purpose").value("SERVICE"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").doesNotExist());

        mvc.perform(get("/payments/" + transfer.paymentId()).with(identity(transfer.fixerSubject())))
                .andExpect(status().isOk());
        mvc.perform(get("/payments/" + transfer.paymentId()).with(identity(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/payments/" + transfer.paymentId()).with(identity(otherOwner)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void onlyTheOwningOwnerCanSimulateAndMissingPaymentsReturnNotFound() throws Exception {
        var transfer = acceptedTransfer("authorization");
        String otherOwner = "auth0|payment-other-simulate";
        provisionWithRole(otherOwner, "OWNER");

        mvc.perform(simulate(transfer.paymentId(), "APPROVED", transfer.fixerSubject()))
                // PostgreSQL RLS hides rows from SELECT FOR UPDATE when the actor cannot update;
                // H2 reaches the application check. Both intentionally deny the fixer.
                .andExpect(status().is4xxClientError());
        mvc.perform(simulate(transfer.paymentId(), "APPROVED", otherOwner))
                .andExpect(status().is4xxClientError());
        mvc.perform(simulate(UUID.randomUUID(), "APPROVED", transfer.ownerSubject()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        assertNoEffects(transfer.quotationId());
    }

    @Test
    void invalidOutcomeIsRejectedAsInvalidJsonEnum() throws Exception {
        var transfer = acceptedTransfer("invalid-outcome");

        for (String invalid : new String[]{"TRANSFER", "CASH", "CARD", "PSE", "SANDBOX_NEQUI"}) {
            mvc.perform(simulate(transfer.paymentId(), invalid, transfer.ownerSubject()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertNoEffects(transfer.quotationId());
    }

    @Test
    void pendingDeclinedAndErrorAreAuditedAndRemainWithoutEffects() throws Exception {
        var transfer = acceptedTransfer("retryable-outcomes");

        for (String outcome : new String[]{"PENDING", "DECLINED", "ERROR"}) {
            mvc.perform(simulate(transfer.paymentId(), outcome, transfer.ownerSubject()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentId").value(transfer.paymentId().toString()))
                    .andExpect(jsonPath("$.status").value(outcome))
                    .andExpect(jsonPath("$.completedAt").doesNotExist());
            assertNoEffects(transfer.quotationId());
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulated_payment_attempts WHERE payment_id = ?",
                Integer.class, transfer.paymentId())).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT requested_outcome FROM simulated_payment_attempts "
                + "WHERE payment_id = ? ORDER BY attempted_at, id", String.class, transfer.paymentId()))
                .containsExactly("PENDING", "DECLINED", "ERROR");
    }

    @Test
    void declinedCanRetryToApprovedWithOneJobAndCorrectHeldEarning() throws Exception {
        var transfer = acceptedTransfer("declined-approved");
        mvc.perform(simulate(transfer.paymentId(), "DECLINED", transfer.ownerSubject()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));

        approveAndAssertEffects(transfer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulated_payment_attempts WHERE payment_id = ?",
                Integer.class, transfer.paymentId())).isEqualTo(2);
    }

    @Test
    void errorCanRetryToApprovedAndApprovalIsIdempotentButTerminal() throws Exception {
        var transfer = acceptedTransfer("error-approved");
        mvc.perform(simulate(transfer.paymentId(), "ERROR", transfer.ownerSubject()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ERROR"));
        approveAndAssertEffects(transfer);

        mvc.perform(simulate(transfer.paymentId(), "APPROVED", transfer.ownerSubject()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertExactlyOneEffect(transfer.quotationId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulated_payment_attempts WHERE payment_id = ?",
                Integer.class, transfer.paymentId())).isEqualTo(2);

        mvc.perform(simulate(transfer.paymentId(), "DECLINED", transfer.ownerSubject()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_APPROVED"));
        assertExactlyOneEffect(transfer.quotationId());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments WHERE id = ?", String.class,
                transfer.paymentId())).isEqualTo("APPROVED");
    }

    @Test
    void twoConcurrentApprovalsPersistExactlyOneJobAndEarning() throws Exception {
        Assumptions.assumeTrue(supportsRealConcurrency(), "Requires PostgreSQL row locks");
        var transfer = acceptedTransfer("concurrent-approved");
        var start = new CountDownLatch(1);
        Callable<Integer> approval = () -> {
            start.await();
            return mvc.perform(simulate(transfer.paymentId(), "APPROVED", transfer.ownerSubject()))
                    .andReturn().getResponse().getStatus();
        };

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(approval);
            var second = pool.submit(approval);
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }

        assertExactlyOneEffect(transfer.quotationId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulated_payment_attempts WHERE payment_id = ?",
                Integer.class, transfer.paymentId())).isEqualTo(1);
    }

    private void approveAndAssertEffects(AcceptedTransfer transfer) throws Exception {
        mvc.perform(simulate(transfer.paymentId(), "APPROVED", transfer.ownerSubject()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        assertExactlyOneEffect(transfer.quotationId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ? "
                        + "AND status = 'HELD' AND gross_amount = 450000 AND commission_amount = 45000 "
                        + "AND net_amount = 405000 AND commission_rate_bps = 1000",
                Integer.class, transfer.quotationId())).isEqualTo(1);
    }

    private void assertNoEffects(UUID quotationId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE quotation_id = ?", Integer.class,
                quotationId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                Integer.class, quotationId)).isZero();
    }

    private void assertExactlyOneEffect(UUID quotationId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE quotation_id = ? AND status = 'ASSIGNED'",
                Integer.class, quotationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ? "
                + "AND status = 'HELD'", Integer.class, quotationId)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder simulate(
            UUID paymentId, String outcome, String subject) {
        return post("/payments/" + paymentId + "/simulate").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"%s\"}".formatted(outcome));
    }

    private AcceptedTransfer acceptedTransfer(String suffix) throws Exception {
        return acceptedQuotation(suffix, "TRANSFER");
    }

    private AcceptedTransfer acceptedCash(String suffix) throws Exception {
        return acceptedQuotation(suffix, "CASH");
    }

    private AcceptedTransfer acceptedCash(String suffix, long amount) throws Exception {
        return acceptedQuotation(suffix, "CASH", amount);
    }

    private AcceptedTransfer acceptedQuotation(String suffix, String method) throws Exception {
        return acceptedQuotation(suffix, method, AMOUNT);
    }

    private AcceptedTransfer acceptedQuotation(String suffix, String method, long amount) throws Exception {
        String owner = "auth0|payment-owner-" + suffix;
        String fixer = "auth0|payment-fixer-" + suffix;
        UUID ownerId = provisionWithRole(owner, "OWNER");
        UUID fixerId = provisionVerifiedFixer(fixer, "PLUMBING");
        UUID propertyId = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertPublished(jdbc, propertyId, ownerId);
        String requestBody = """
                {"propertyId":"%s","title":"Fuga de agua","description":"Reparar tuberia principal",
                 "mediaIds":[],"paymentMethod":"%s"}
                """.formatted(propertyId, method);
        var requestResult = mvc.perform(post("/requests").with(identity(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated()).andReturn();
        UUID requestId = UUID.fromString(mapper.readTree(requestResult.getResponse().getContentAsString())
                .get("requestId").asText());
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", requestId);
        String quotationBody = """
                {"requestId":"%s","amount":%d,"estimatedDays":2,
                 "message":"Materiales y mano de obra incluidos"}
                """.formatted(requestId, amount);
        var quotationResult = mvc.perform(post("/quotations").with(identity(fixer))
                        .contentType(MediaType.APPLICATION_JSON).content(quotationBody))
                .andExpect(status().isCreated()).andReturn();
        UUID quotationId = UUID.fromString(mapper.readTree(quotationResult.getResponse().getContentAsString())
                .get("id").asText());
        var acceptance = mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(owner)))
                .andExpect(status().isOk()).andReturn();
        UUID paymentId = null;
        if (method.equals("TRANSFER")) {
            paymentId = UUID.fromString(mapper.readTree(acceptance.getResponse().getContentAsString())
                    .get("paymentId").asText());
            assertNoEffects(quotationId);
        } else {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE quotation_id = ?",
                    Integer.class, quotationId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                    Integer.class, quotationId)).isZero();
        }
        return new AcceptedTransfer(paymentId, quotationId, requestId, ownerId, fixerId, owner, fixer);
    }

    @Test
    void transferFinishesRequestAndPayoutWithExactAmounts() throws Exception {
        var transfer = acceptedTransfer("final-transfer");
        mvc.perform(simulate(transfer.paymentId(), "APPROVED", transfer.ownerSubject()))
                .andExpect(status().isOk());
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                transfer.quotationId());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(transfer.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CASH_RECEIVED_AMOUNT"));
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(transfer.fixerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("TRANSFER"))
                .andExpect(jsonPath("$.agreedAmount").value(AMOUNT));
        assertThat(jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?",
                String.class, transfer.requestId())).isEqualTo("COMPLETED");
        mvc.perform(get("/requests/me").with(identity(transfer.ownerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT status FROM fixer_earnings WHERE quotation_id = ?",
                String.class, transfer.quotationId())).isEqualTo("AVAILABLE");
        mvc.perform(post("/payments/me/payouts").with(identity(transfer.fixerSubject())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TRANSFERRED"))
                .andExpect(jsonPath("$.amount").value(405_000))
                .andExpect(jsonPath("$.transferredAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT status FROM fixer_earnings WHERE quotation_id = ?",
                String.class, transfer.quotationId())).isEqualTo("PAID_OUT");
    }

    @Test
    void cashWholePesoString50000CompletesWithoutDebtOrEarning() throws Exception {
        var cash = acceptedCash("whole-peso-50000", 50_000L);
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                cash.quotationId());

        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":\"50000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?",
                String.class, cash.requestId())).isEqualTo("COMPLETED");
        mvc.perform(get("/payments/cash-settlements/jobs/" + jobId)
                        .with(identity(cash.ownerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreedAmount").value(50_000))
                .andExpect(jsonPath("$.receivedAmount").value(50_000))
                .andExpect(jsonPath("$.outstandingAmount").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_debts WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
    }

    @Test
    void cashWholePesoString100000AlsoCompletes() throws Exception {
        var cash = acceptedCash("whole-peso-100000", 100_000L);
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                cash.quotationId());

        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":\"100000\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT outstanding_amount FROM cash_settlements WHERE job_id = ?",
                Long.class, jobId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_debts WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
    }

    @Test
    void cashWholePesoString30000CreatesOnlyThe20000Debt() throws Exception {
        var cash = acceptedCash("whole-peso-30000", 50_000L);
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                cash.quotationId());

        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":\"30000\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/payments/cash-settlements/jobs/" + jobId)
                        .with(identity(cash.ownerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreedAmount").value(50_000))
                .andExpect(jsonPath("$.receivedAmount").value(30_000))
                .andExpect(jsonPath("$.outstandingAmount").value(20_000));
        assertThat(jdbc.queryForObject("SELECT status FROM owner_debts WHERE quotation_id = ?",
                String.class, cash.quotationId())).isEqualTo("OUTSTANDING");
        assertThat(jdbc.queryForObject("SELECT amount FROM owner_debts WHERE quotation_id = ?",
                Long.class, cash.quotationId())).isEqualTo(20_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
    }

    @Test
    void cashDecimalStringsCannotBeRoundedToWholePesos() throws Exception {
        var cash = acceptedCash("cash-decimal-strings", 50_000L);
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                cash.quotationId());

        for (String value : new String[] {"0.01", "1.50", "49999.99"}) {
            mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receivedAmount\":\"" + value + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_CASH_RECEIVED_AMOUNT"));
        }
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class,
                jobId)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_settlements WHERE job_id = ?",
                Integer.class, jobId)).isZero();
    }

    @Test
    void fullCashCompletionCreatesOnlyAnAuditedSettlement() throws Exception {
        var cash = acceptedCash("final-cash-full");
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                cash.quotationId());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CASH_RECEIVED_AMOUNT"));
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":450001}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":-1}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":100.5}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":\"100.5\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":450000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/payments/cash-settlements/jobs/" + jobId)
                        .with(identity(cash.ownerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.receivedAmount").value(AMOUNT))
                .andExpect(jsonPath("$.outstandingAmount").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?",
                String.class, cash.requestId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_debts WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
        mvc.perform(post("/requests/" + cash.requestId() + "/messages")
                        .with(identity(cash.ownerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Confirmemos el saldo pendiente\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":450000}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_settlements WHERE job_id = ?",
                Integer.class, jobId)).isEqualTo(1);
    }

    @Test
    void cashJobMayCompleteWithZeroReceived() throws Exception {
        var cash = acceptedCash("final-cash-zero", 50_000L);
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                cash.quotationId());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":\"0\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT outstanding_amount FROM cash_settlements WHERE job_id = ?",
                Long.class, jobId)).isEqualTo(50_000L);
        assertThat(jdbc.queryForObject("SELECT amount FROM owner_debts WHERE quotation_id = ?",
                Long.class, cash.quotationId())).isEqualTo(50_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
    }

    @Test
    void partialCashDebtBlocksCreationThenTransferApprovalUnlocksOwner() throws Exception {
        var cash = acceptedCash("final-cash-partial");
        UUID jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class,
                cash.quotationId());
        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity(cash.fixerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":410000}"))
                .andExpect(status().isOk());
        UUID debtId = jdbc.queryForObject("SELECT id FROM owner_debts WHERE quotation_id = ?",
                UUID.class, cash.quotationId());
        String unrelatedOwner = "auth0|other-debt-owner";
        provisionWithRole(unrelatedOwner, "OWNER");
        mvc.perform(get("/payments/cash-settlements/jobs/" + jobId)
                .with(identity(cash.fixerSubject()))).andExpect(status().isOk());
        mvc.perform(get("/payments/cash-settlements/jobs/" + jobId)
                .with(identity(unrelatedOwner))).andExpect(status().is4xxClientError());
        mvc.perform(get("/payments/me/debts").with(identity(unrelatedOwner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
        mvc.perform(post("/payments/debts/" + debtId + "/pay")
                .with(identity(unrelatedOwner))).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT amount FROM owner_debts WHERE id = ?", Long.class,
                debtId)).isEqualTo(40_000L);
        mvc.perform(post("/requests/" + cash.requestId() + "/messages")
                        .with(identity(cash.ownerSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Confirmemos el saldo pendiente\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isZero();
        mvc.perform(get("/payments/me/debts").with(identity(cash.ownerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].debtId").value(debtId.toString()))
                .andExpect(jsonPath("$[0].amount").value(40_000));
        UUID propertyId = jdbc.queryForObject("SELECT property_id FROM repair_requests WHERE id = ?",
                UUID.class, cash.requestId());
        String requestBody = """
                {"propertyId":"%s","title":"Nueva fuga","description":"Reparar tuberia",
                 "mediaIds":[],"paymentMethod":"TRANSFER"}
                """.formatted(propertyId);
        mvc.perform(post("/requests").with(identity(cash.ownerSubject()))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_HAS_OUTSTANDING_DEBT"));
        String remodelingBody = """
                {"propertyId":"%s","title":"Proyecto nuevo","requirements":[
                 {"title":"Nueva fuga","description":"Reparar tuberia","mediaIds":[],
                  "paymentMethod":"CASH"}]}
                """.formatted(propertyId);
        mvc.perform(post("/remodelings").with(identity(cash.ownerSubject()))
                        .contentType(MediaType.APPLICATION_JSON).content(remodelingBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_HAS_OUTSTANDING_DEBT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remodeling_projects WHERE owner_user_id = ?",
                Integer.class, cash.ownerId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_requests WHERE owner_user_id = ?",
                Integer.class, cash.ownerId())).isEqualTo(1);
        var initiated = mvc.perform(post("/payments/debts/" + debtId + "/pay")
                        .with(identity(cash.ownerSubject())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("OUTSTANDING_BALANCE"))
                .andExpect(jsonPath("$.amount").value(40_000)).andReturn();
        UUID paymentId = UUID.fromString(mapper.readTree(initiated.getResponse().getContentAsString())
                .get("paymentId").asText());
        mvc.perform(post("/payments/debts/" + debtId + "/pay")
                .with(identity(cash.ownerSubject())))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()));
        mvc.perform(simulate(paymentId, "DECLINED", cash.ownerSubject()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM owner_debts WHERE id = ?", String.class,
                debtId)).isEqualTo("OUTSTANDING");
        mvc.perform(simulate(paymentId, "APPROVED", cash.ownerSubject()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM owner_debts WHERE id = ?", String.class,
                debtId)).isEqualTo("PAID");
        mvc.perform(get("/payments/me/debts").with(identity(cash.ownerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT gross_amount FROM fixer_earnings WHERE quotation_id = ?",
                Long.class, cash.quotationId())).isEqualTo(40_000L);
        assertThat(jdbc.queryForObject("SELECT commission_amount FROM fixer_earnings WHERE quotation_id = ?",
                Long.class, cash.quotationId())).isEqualTo(4_000L);
        assertThat(jdbc.queryForObject("SELECT net_amount FROM fixer_earnings WHERE quotation_id = ?",
                Long.class, cash.quotationId())).isEqualTo(36_000L);
        assertThat(jdbc.queryForObject("SELECT status FROM fixer_earnings WHERE quotation_id = ?",
                String.class, cash.quotationId())).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE quotation_id = ?",
                Integer.class, cash.quotationId())).isEqualTo(1);
        mvc.perform(post("/requests").with(identity(cash.ownerSubject()))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());
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

    private UUID provisionVerifiedFixer(String subject, String specialty) throws Exception {
        UUID fixerId = provisionWithRole(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixerId);
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, ?)", fixerId, specialty);
        return fixerId;
    }

    private record AcceptedTransfer(UUID paymentId, UUID quotationId, UUID requestId,
            UUID ownerId, UUID fixerId, String ownerSubject, String fixerSubject) {
    }
}
