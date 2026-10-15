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
        String owner = "auth0|payment-owner-" + suffix;
        String fixer = "auth0|payment-fixer-" + suffix;
        UUID ownerId = provisionWithRole(owner, "OWNER");
        UUID fixerId = provisionVerifiedFixer(fixer, "PLUMBING");
        UUID propertyId = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertPublished(jdbc, propertyId, ownerId);
        String requestBody = """
                {"propertyId":"%s","title":"Fuga de agua","description":"Reparar tuberia principal",
                 "mediaIds":[],"paymentMethod":"TRANSFER"}
                """.formatted(propertyId);
        var requestResult = mvc.perform(post("/requests").with(identity(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated()).andReturn();
        UUID requestId = UUID.fromString(mapper.readTree(requestResult.getResponse().getContentAsString())
                .get("requestId").asText());
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", requestId);
        String quotationBody = """
                {"requestId":"%s","amount":%d,"estimatedDays":2,
                 "message":"Materiales y mano de obra incluidos"}
                """.formatted(requestId, AMOUNT);
        var quotationResult = mvc.perform(post("/quotations").with(identity(fixer))
                        .contentType(MediaType.APPLICATION_JSON).content(quotationBody))
                .andExpect(status().isCreated()).andReturn();
        UUID quotationId = UUID.fromString(mapper.readTree(quotationResult.getResponse().getContentAsString())
                .get("id").asText());
        var acceptance = mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").isNotEmpty()).andReturn();
        UUID paymentId = UUID.fromString(mapper.readTree(acceptance.getResponse().getContentAsString())
                .get("paymentId").asText());
        assertNoEffects(quotationId);
        return new AcceptedTransfer(paymentId, quotationId, requestId, ownerId, fixerId, owner, fixer);
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
