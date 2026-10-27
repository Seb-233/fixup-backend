package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.payments.api.PaymentApproved;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import com.fixup.testsupport.PropertyFixtures;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.event.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real gateway, HTTP, transaction coordinator, immutable audit and effective PostgreSQL RLS. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@RecordApplicationEvents
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class PostgresDigitalPaymentIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("springdoc.api-docs.enabled", () -> true);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationDatabaseCleaner cleaner;
    @Autowired ConfigurableEnvironment environment;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEvents events;
    private final Map<String, Object> sandbox = new HashMap<>();
    private UUID owner, fixer, manager, property, request, quotation, payment;
    private String reference;

    @BeforeEach void setup() {
        cleaner.clean();
        sandbox.put("fixup.payments.sandbox.scenario", "APPROVE");
        environment.getPropertySources().addFirst(new MapPropertySource("digital-payment-test", sandbox));
    }
    @AfterEach void cleanup() {
        for (String table : List.of("fixer_earnings", "jobs", "job_status_log", "payouts", "payout_entries"))
            jdbc.execute("DROP TRIGGER IF EXISTS fr053_fail ON " + table);
        jdbc.execute("DROP FUNCTION IF EXISTS fr053_fail()");
        environment.getPropertySources().remove("digital-payment-test");
        cleaner.clean();
    }

    @ParameterizedTest @ValueSource(strings = {"SANDBOX_CARD", "SANDBOX_PSE", "SANDBOX_NEQUI"})
    void ownerPaysExactlyOnceAndReplayPreservesTheOriginalResult(String instrument) throws Exception {
        accepted(false, "TRANSFER");
        assertThat(count("jobs")).isZero();
        assertThat(count("fixer_earnings")).isZero();
        UUID key = UUID.randomUUID();
        var first = pay("owner", key, instrument).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.attemptedByUserId").value(owner.toString())).andReturn();
        var before = financialSnapshot();
        var repeat = pay("owner", key, instrument).andExpect(status().isOk()).andReturn();
        assertThat(repeat.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(financialSnapshot()).isEqualTo(before);
        pay("owner", key, instrument.equals("SANDBOX_CARD") ? "SANDBOX_PSE" : "SANDBOX_CARD")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        pay("manager", key, instrument).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(attempt("owner", key, body(reference, "99999", instrument))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        pay("owner", UUID.randomUUID(), instrument).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_APPROVED"));
        assertApprovedOnce();
        assertThat(events.stream(PaymentApproved.class)).hasSize(1);
        report("owner", 100000, 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'PAYMENT_APPROVED'", Integer.class)).isEqualTo(2);
        mvc.perform(get("/payments/" + payment + "/attempts").with(identity("fixer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].gatewayTransactionId").isNotEmpty());
    }

    @Test void currentManagerCanInitiateReadAndPayWithoutImpersonatingTheOwner() throws Exception {
        accepted(true, "TRANSFER");
        mvc.perform(get("/payments/" + payment).with(identity("manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.context.ownerUserId").value(owner.toString()))
                .andExpect(jsonPath("$.context.managerUserId").value(manager.toString()));
        pay("manager", UUID.randomUUID(), "SANDBOX_PSE").andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptedByUserId").value(manager.toString()));
        assertApprovedOnce();
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM repair_payments", UUID.class)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT attempted_by_user_id FROM simulated_payment_attempts", UUID.class)).isEqualTo(manager);
        assertThat(jdbc.queryForObject("SELECT changed_by_user_id FROM job_status_log", UUID.class)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'PAYMENT_APPROVED'", Integer.class)).isEqualTo(3);
        report("owner", 100000, 1);
        report("manager", 100000, 1);
        jdbc.update("UPDATE properties SET manager_user_id = NULL WHERE id = ?", property);
        report("manager", 0, 0);
        mvc.perform(get("/payments/" + payment + "/attempts").with(identity("manager"))).andExpect(status().is4xxClientError());
    }

    @Test void replacementManagerCanPayButTheHistoricalManagerCannot() throws Exception {
        accepted(true, "TRANSFER");
        UUID replacement = provision("replacement", "REAL_ESTATE_MANAGER");
        jdbc.update("UPDATE properties SET manager_user_id = ? WHERE id = ?", replacement, property);
        pay("manager", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().is4xxClientError());
        assertThat(count("simulated_payment_attempts")).isZero();
        pay("replacement", UUID.randomUUID(), "SANDBOX_NEQUI").andExpect(status().isOk());
        assertApprovedOnce();
        assertThat(jdbc.queryForObject("SELECT manager_user_id FROM repair_payment_contexts", UUID.class)).isEqualTo(manager);
        report("replacement", 100000, 1);
        report("manager", 0, 0);
    }

    @ParameterizedTest @ValueSource(strings = {"OWNER", "REAL_ESTATE_MANAGER", "TENANT", "FIXER"})
    void unrelatedRolesCannotPayOrReadFinancialRows(String role) throws Exception {
        accepted(false, "TRANSFER");
        UUID stranger = provision("stranger", role);
        pay("stranger", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().is4xxClientError());
        mvc.perform(get("/payments/" + payment).with(identity("stranger"))).andExpect(status().is4xxClientError());
        assertThat(asActor(stranger, role, () -> count("repair_payments"))).isZero();
        if (role.equals("OWNER") || role.equals("REAL_ESTATE_MANAGER")) report("stranger", 0, 0);
        else mvc.perform(reportRequest("stranger")).andExpect(status().isForbidden());
        assertThat(count("simulated_payment_attempts")).isZero();
    }

    @Test void suspendedOrPersistentlyRevokedRoleCannotPayEvenWithStaleSessionClaims() throws Exception {
        accepted(true, "TRANSFER");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", manager);
        pay("manager", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().is4xxClientError());
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> count("repair_payments"))).isZero();
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", manager);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", manager);
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> count("repair_payments"))).isZero();
        assertThat(count("simulated_payment_attempts")).isZero();
    }

    @Test void platformAuditDoesNotGrantPermissionToPayForAnUnrelatedOwner() throws Exception {
        accepted(false, "TRANSFER");
        provision("admin", "PLATFORM_ADMIN");
        mvc.perform(get("/payments/" + payment).with(identity("admin"))).andExpect(status().isOk());
        pay("admin", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().is4xxClientError());
        pay("owner", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().isOk());
        report("admin", 100000, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "DECLINE_FUNDS", "DECLINE_BANK", "ERROR"})
    void retryableGatewayResultsHaveNoEconomicEffectAndReplayDoesNotChangeAfterApproval(String scenario) throws Exception {
        accepted(false, "TRANSFER");
        sandbox.put("fixup.payments.sandbox.scenario", scenario);
        UUID rejectedKey = UUID.randomUUID();
        var rejected = pay("owner", rejectedKey, "SANDBOX_CARD").andExpect(status().isOk()).andReturn();
        assertThat(count("jobs")).isZero();
        assertThat(count("fixer_earnings")).isZero();
        report("owner", 0, 0);
        var result = mapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(result.get("status").asText()).isEqualTo(scenario.startsWith("DECLINE") ? "DECLINED" : scenario);
        if (!scenario.equals("PENDING")) assertThat(result.get("failureReason").asText()).isNotBlank();
        sandbox.put("fixup.payments.sandbox.scenario", "APPROVE");
        pay("owner", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().isOk());
        var replay = pay("owner", rejectedKey, "SANDBOX_CARD").andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(rejected.getResponse().getContentAsString());
        assertThat(count("simulated_payment_attempts")).isEqualTo(2);
        assertApprovedOnce();
        report("owner", 100000, 1);
    }

    @Test void badReferenceAmountInstrumentScenarioAndClientOutcomeHaveNoSideEffects() throws Exception {
        accepted(false, "TRANSFER");
        var original = financialSnapshot();
        for (String body : List.of(
                body("wrong", "100000", "SANDBOX_CARD"), body(reference, "100001", "SANDBOX_CARD"),
                body(reference, "100000.5", "SANDBOX_CARD"), body(reference, "9223372036854775808", "SANDBOX_CARD"),
                body(reference, "100000", "TRANSFER"), body(reference, "100000", "SANDBOX_CARD").replace("}", ",\"outcome\":\"APPROVED\"}"))) {
            mvc.perform(attempt("owner", UUID.randomUUID(), body)).andExpect(status().is4xxClientError());
        }
        sandbox.put("fixup.payments.sandbox.scenario", "TYPO");
        pay("owner", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SANDBOX_SCENARIO"));
        mvc.perform(post("/payments/" + payment + "/attempts").with(identity("owner"))
                .contentType(MediaType.APPLICATION_JSON).content(body(reference, "100000", "SANDBOX_CARD")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/payments/" + payment + "/attempts").with(identity("owner"))
                .header("Idempotency-Key", "not-a-uuid").contentType(MediaType.APPLICATION_JSON)
                .content(body(reference, "100000", "SANDBOX_CARD"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(financialSnapshot()).isEqualTo(original);
    }

    @Test void theDatabaseRejectsDuplicateOperationKeysIndependentlyOfTheProcessor() throws Exception {
        accepted(false, "TRANSFER");
        sandbox.put("fixup.payments.sandbox.scenario", "PENDING");
        pay("owner", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().isOk());
        assertThatThrownBy(() -> asActor(owner, "OWNER", () -> jdbc.update("""
                INSERT INTO simulated_payment_attempts(id,payment_id,requested_outcome,resulting_status,
                    attempted_by_user_id,attempted_at,operation_key,payment_reference,amount,instrument,scenario,gateway_transaction_id)
                SELECT ?,payment_id,requested_outcome,resulting_status,attempted_by_user_id,attempted_at,
                    operation_key,payment_reference,amount,instrument,scenario,'SBX-duplicate-key'
                FROM simulated_payment_attempts WHERE payment_id = ?
                """, UUID.randomUUID(), payment))).hasStackTraceContaining("uq_payment_operation");
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentApprovalsConsolidateOneOperation(boolean sameKey) throws Exception {
        accepted(false, "TRANSFER");
        UUID key = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return pay("owner", key, "SANDBOX_CARD").andReturn(); });
            var second = pool.submit(() -> { start.await(); return pay("owner", sameKey ? key : UUID.randomUUID(), "SANDBOX_CARD").andReturn(); });
            start.countDown();
            var a = first.get(30, TimeUnit.SECONDS).getResponse();
            var b = second.get(30, TimeUnit.SECONDS).getResponse();
            assertThat(List.of(a.getStatus(), b.getStatus())).containsExactlyInAnyOrder(200, sameKey ? 200 : 409);
            if (sameKey) assertThat(a.getContentAsString()).isEqualTo(b.getContentAsString());
        }
        assertThat(count("simulated_payment_attempts")).isEqualTo(1);
        assertApprovedOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'PAYMENT_APPROVED'", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"fixer_earnings", "jobs", "job_status_log"})
    void sqlFailureRollsBackPaymentAttemptEarningJobLogAndNotifications(String table) throws Exception {
        accepted(false, "TRANSFER");
        var before = financialSnapshot();
        jdbc.execute("CREATE FUNCTION fr053_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'FR053_SQL_FAILURE'; END $$");
        jdbc.execute("CREATE TRIGGER fr053_fail AFTER INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION fr053_fail()");
        assertThatThrownBy(() -> pay("owner", UUID.randomUUID(), "SANDBOX_CARD")).hasStackTraceContaining("FR053_SQL_FAILURE");
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'PAYMENT_APPROVED'", Integer.class)).isZero();
    }

    @Test void effectiveRlsRejectsForgedActorMutationAndCrossOwnerPayments() throws Exception {
        accepted(true, "TRANSFER");
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> jdbc.queryForObject("SELECT current_user", String.class))).isEqualTo("fixup_app");
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> count("repair_payments"))).isEqualTo(1);
        assertThatThrownBy(() -> asActor(manager, "REAL_ESTATE_MANAGER", () -> jdbc.update("""
                INSERT INTO jobs(id,request_id,quotation_id,fixer_user_id,owner_user_id,status,created_at,updated_at,payment_method,agreed_amount)
                VALUES (?,?,?,?,?,'ASSIGNED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TRANSFER',100000)
                """, UUID.randomUUID(), request, quotation, fixer, owner)))
                .hasStackTraceContaining("Job opening requires an authorized canonical payment");
        UUID forgedPayment = UUID.randomUUID();
        assertThatThrownBy(() -> asActor(manager, "REAL_ESTATE_MANAGER", () -> jdbc.update("""
                INSERT INTO repair_payments(id,quotation_id,request_id,owner_user_id,fixer_user_id,amount,
                    payment_method,purpose,status,created_at,updated_at,reference)
                VALUES (?,?,?,?,?,1,'TRANSFER','SERVICE','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)
                """, forgedPayment, quotation, request, owner, fixer, "FXR-" + forgedPayment.toString().replace("-", "").toUpperCase(Locale.ROOT))))
                .hasStackTraceContaining("row-level security");
        assertThatThrownBy(() -> asActor(manager, "REAL_ESTATE_MANAGER", () -> jdbc.update("""
                INSERT INTO simulated_payment_attempts(id,payment_id,requested_outcome,resulting_status,attempted_by_user_id,
                attempted_at,operation_key,payment_reference,amount,instrument,scenario,gateway_transaction_id)
                VALUES (?,?,'APPROVED','APPROVED',?,CURRENT_TIMESTAMP,?,?,100000,'SANDBOX_CARD','APPROVE','SBX-forged')
                """, UUID.randomUUID(), payment, owner, UUID.randomUUID(), reference))).hasStackTraceContaining("row-level security");
        pay("manager", UUID.randomUUID(), "SANDBOX_CARD").andExpect(status().isOk());
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> jdbc.update("UPDATE simulated_payment_attempts SET failure_reason = 'forged'"))).isZero();
        assertThat(asActor(owner, "OWNER", () -> jdbc.update("DELETE FROM simulated_payment_attempts"))).isZero();
        assertThat(asActor(owner, "OWNER", () -> jdbc.update("UPDATE repair_payment_contexts SET concept = 'forged'"))).isZero();
        assertThatThrownBy(() -> asActor(owner, "OWNER", () -> jdbc.update("UPDATE repair_payments SET amount = 1 WHERE id = ?", payment)))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> asActor(owner, "OWNER", () -> jdbc.update("UPDATE repair_payments SET status = 'DECLINED', completed_at = NULL WHERE id = ?", payment)))
                .hasStackTraceContaining("immutable");
        jdbc.update("UPDATE properties SET manager_user_id = NULL WHERE id = ?", property);
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> count("repair_payments"))).isZero();
        assertThat(asActor(manager, "REAL_ESTATE_MANAGER", () -> count("simulated_payment_attempts"))).isZero();
    }

    @Test void historicalCashAndPaidDebtAreCountedOnceAndIncompleteContextIsExplicit() throws Exception {
        accepted(true, "CASH");
        UUID job = jdbc.queryForObject("SELECT id FROM jobs", UUID.class);
        mvc.perform(post("/jobs/" + job + "/complete").with(identity("fixer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"receivedAmount\":60000}")).andExpect(status().isOk());
        report("owner", 60000, 1);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", owner);
        assertThat(asActor(owner, "OWNER", () -> count("cash_settlements"))).isZero();
        assertThat(asActor(owner, "OWNER", () -> count("owner_debts"))).isZero();
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", owner);
        UUID tenant = provision("tenant", "TENANT");
        assertThat(asActor(tenant, "PLATFORM_ADMIN", () -> count("cash_settlements"))).isZero();
        assertThat(asActor(tenant, "PLATFORM_ADMIN", () -> count("owner_debts"))).isZero();
        UUID debt = jdbc.queryForObject("SELECT id FROM owner_debts", UUID.class);
        var initiated = mvc.perform(post("/payments/debts/" + debt + "/pay").with(identity("manager"))).andExpect(status().isCreated()).andReturn();
        payment = uuid(json(initiated), "paymentId");
        reference = json(initiated).get("reference").asText();
        mvc.perform(attempt("manager", UUID.randomUUID(), body(reference, "40000", "SANDBOX_PSE"))).andExpect(status().isOk());
        report("owner", 100000, 2);
        report("manager", 100000, 2);
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("fixer_earnings")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM owner_debts", String.class)).isEqualTo("PAID");
        // Fixture represents an unreconstructable old financial fact, not an application mutation.
        jdbc.update("DELETE FROM repair_payment_contexts");
        mvc.perform(reportRequest("owner")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPENSE_CONTEXT_INCOMPLETE"));
    }

    @Test void creationDefaultsToDigitalAndRejectsExplicitCashNullAndUnknownFields() throws Exception {
        accepted(false, "TRANSFER");
        String base = "{\"propertyId\":\"" + property + "\",\"title\":\"Fuga\",\"description\":\"Reparar tuberia\",\"mediaIds\":[]";
        for (String suffix : List.of("}", ",\"paymentMethod\":\"TRANSFER\"}"))
            mvc.perform(post("/requests").with(identity("owner")).contentType(MediaType.APPLICATION_JSON).content(base+suffix))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.paymentMethod").value("TRANSFER"));
        int before = count("repair_requests");
        for (String suffix : List.of(",\"paymentMethod\":\"CASH\"}",",\"paymentMethod\":null}",",\"paymentMethod\":\"OTHER\"}",",\"legacy_cash\":true}"))
            mvc.perform(post("/requests").with(identity("owner")).contentType(MediaType.APPLICATION_JSON).content(base+suffix))
                    .andExpect(status().isBadRequest());
        assertThat(count("repair_requests")).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.update("UPDATE repair_requests SET payment_method='CASH' WHERE id=?",request))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE repair_requests SET legacy_cash=TRUE WHERE id=?",request))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO jobs(id,request_id,quotation_id,fixer_user_id,owner_user_id,status,created_at,updated_at,payment_method,agreed_amount,legacy_cash) VALUES (?,?,?,?,?,'ASSIGNED',TIMESTAMPTZ '2020-01-01Z',CURRENT_TIMESTAMP,'CASH',100000,TRUE)",UUID.randomUUID(),request,quotation,fixer,owner))
                .hasStackTraceContaining("DIGITAL_PAYMENT_REQUIRED");
    }

    @Test void payoutReplayDoesNotConsumeNewEarningsAndPreservesExactComposition() throws Exception {
        accepted(false,"TRANSFER");
        pay("owner",UUID.randomUUID(),"SANDBOX_CARD").andExpect(status().isOk());
        payout("fixer",UUID.randomUUID()).andExpect(status().isConflict());
        completeCurrentJob();
        UUID key=UUID.randomUUID();
        var first=payout("fixer",key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(90000)).andExpect(jsonPath("$.earningCount").value(1))
                .andExpect(jsonPath("$.detailStatus").value("RECORDED"))
                .andExpect(jsonPath("$.earnings[0].netAmount").value(90000)).andReturn();
        var oldEntries=jdbc.queryForList("SELECT * FROM payout_entries");
        anotherApprovedService(); completeCurrentJob();
        var repeat=payout("fixer",key).andExpect(status().isCreated()).andReturn();
        assertThat(repeat.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForList("SELECT * FROM payout_entries")).isEqualTo(oldEntries);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE status='AVAILABLE'",Integer.class)).isEqualTo(1);
        payout("fixer",UUID.randomUUID()).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(90000));
        assertThat(count("payouts")).isEqualTo(2); assertThat(count("payout_entries")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM payouts",Long.class)).isEqualTo(180000);
        assertThat(jdbc.queryForObject("SELECT sum(net_amount) FROM payout_entries",Long.class)).isEqualTo(180000);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentWithdrawalsNeverConsumeAnEarningTwice(boolean sameKey) throws Exception {
        accepted(false,"TRANSFER"); pay("owner",UUID.randomUUID(),"SANDBOX_CARD").andExpect(status().isOk()); completeCurrentJob();
        UUID key=UUID.randomUUID(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return payout("fixer",key).andReturn().getResponse();});
            var b=pool.submit(()->{start.await();return payout("fixer",sameKey?key:UUID.randomUUID()).andReturn().getResponse();});
            start.countDown(); var one=a.get(30,TimeUnit.SECONDS); var two=b.get(30,TimeUnit.SECONDS);
            assertThat(List.of(one.getStatus(),two.getStatus())).containsExactlyInAnyOrder(201,sameKey?201:409);
            if(sameKey) assertThat(one.getContentAsString()).isEqualTo(two.getContentAsString());
        }
        assertThat(count("payouts")).isEqualTo(1); assertThat(count("payout_entries")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"payouts","payout_entries","fixer_earnings"})
    void payoutFailureRollsBackCompositionAndBalances(String table) throws Exception {
        accepted(false,"TRANSFER"); pay("owner",UUID.randomUUID(),"SANDBOX_CARD").andExpect(status().isOk()); completeCurrentJob();
        var before=jdbc.queryForList("SELECT * FROM fixer_earnings"); UUID key=UUID.randomUUID();
        jdbc.execute("CREATE FUNCTION fr053_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'FR054_PAYOUT_FAILURE'; END $$");
        jdbc.execute("CREATE TRIGGER fr053_fail AFTER " + (table.equals("fixer_earnings")?"UPDATE":"INSERT") + " ON "+table+" FOR EACH ROW EXECUTE FUNCTION fr053_fail()");
        assertThatThrownBy(()->payout("fixer",key)).hasStackTraceContaining("FR054_PAYOUT_FAILURE");
        assertThat(count("payouts")).isZero(); assertThat(count("payout_entries")).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM fixer_earnings")).isEqualTo(before);
        jdbc.execute("DROP TRIGGER fr053_fail ON "+table);
        payout("fixer",key).andExpect(status().isCreated());
    }

    @Test void payoutRlsSeparatesActorsRejectsTamperingAndChecksTotalsAtCommit() throws Exception {
        accepted(true,"TRANSFER"); pay("manager",UUID.randomUUID(),"SANDBOX_CARD").andExpect(status().isOk()); completeCurrentJob();
        UUID other=provision("otherfixer","FIXER"),tenant=provision("tenant","TENANT"),admin=provision("audit","PLATFORM_ADMIN");
        UUID key=UUID.randomUUID();
        mvc.perform(post("/payments/me/payouts").with(identity("fixer"))).andExpect(status().isBadRequest());
        mvc.perform(post("/payments/me/payouts").with(identity("fixer")).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}")).andExpect(status().isBadRequest());
        assertThatThrownBy(()->asActor(fixer,"FIXER",()->jdbc.update("INSERT INTO payouts(id,fixer_user_id,amount,earning_count,status,requested_at,transferred_at,operation_key) VALUES (?,?,1,1,'TRANSFERRED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",UUID.randomUUID(),fixer,UUID.randomUUID())))
                .hasStackTraceContaining("Payout total");
        payout("fixer",key).andExpect(status().isCreated());
        payout("otherfixer",key).andExpect(status().isConflict());
        for(var entry:Map.of(owner,"OWNER",manager,"REAL_ESTATE_MANAGER",other,"FIXER",tenant,"TENANT",admin,"PLATFORM_ADMIN").entrySet()) {
            assertThat(asActor(entry.getKey(),entry.getValue(),()->count("payouts"))).isZero();
            assertThat(asActor(entry.getKey(),entry.getValue(),()->count("payout_entries"))).isZero();
        }
        assertThat(asActor(fixer,"FIXER",()->jdbc.update("UPDATE payouts SET amount=1"))).isZero();
        assertThat(asActor(fixer,"FIXER",()->jdbc.update("UPDATE payout_entries SET net_amount=1"))).isZero();
        assertThatThrownBy(()->asActor(fixer,"FIXER",()->jdbc.update("UPDATE fixer_earnings SET net_amount=1"))).hasStackTraceContaining("immutable");
        assertThatThrownBy(()->asActor(fixer,"FIXER",()->jdbc.update("UPDATE fixer_earnings SET status='AVAILABLE',paid_out_at=NULL"))).hasStackTraceContaining("Invalid earning transition");
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?",fixer);
        payout("fixer",key).andExpect(status().isForbidden());
        assertThat(asActor(fixer,"FIXER",()->count("payouts"))).isZero();
        assertThat(asActor(fixer,"FIXER",()->count("fixer_earnings"))).isZero();
    }

    private ResultActions payout(String actor,UUID key) throws Exception {
        return mvc.perform(post("/payments/me/payouts").with(identity(actor)).header("Idempotency-Key",key));
    }
    @Test void payoutAggregatesOnlyAvailableEarningsAndRejectsForgedMembership() throws Exception {
        accepted(false,"TRANSFER"); pay("owner",UUID.randomUUID(),"SANDBOX_CARD").andExpect(status().isOk());
        UUID held=jdbc.queryForObject("SELECT id FROM fixer_earnings",UUID.class);
        assertThatThrownBy(()->asActor(fixer,"FIXER",()->jdbc.update("UPDATE fixer_earnings SET status='AVAILABLE',released_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",held)))
                .hasStackTraceContaining("Earning release requires completed job");
        assertThatThrownBy(()->asActor(fixer,"FIXER",()-> {
            UUID p=UUID.randomUUID();
            jdbc.update("INSERT INTO payouts(id,fixer_user_id,amount,earning_count,status,requested_at,transferred_at,operation_key) VALUES (?,?,90000,1,'TRANSFERRED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",p,fixer,UUID.randomUUID());
            return jdbc.update("INSERT INTO payout_entries(earning_id,payout_id,fixer_user_id,net_amount) VALUES (?,?,?,90000)",held,p,fixer);
        })).hasStackTraceContaining("Payout entry requires an available earning");
        anotherApprovedService(); completeCurrentJob();
        anotherApprovedService(); completeCurrentJob();
        var result=payout("fixer",UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(180000)).andExpect(jsonPath("$.earningCount").value(2))
                .andExpect(jsonPath("$.earnings.length()").value(2)).andReturn();
        assertThat(jdbc.queryForObject("SELECT status FROM fixer_earnings WHERE id=?",String.class,held)).isEqualTo("HELD");
        UUID paid=jdbc.queryForObject("SELECT earning_id FROM payout_entries LIMIT 1",UUID.class);
        UUID p=uuid(json(result),"id");
        assertThatThrownBy(()->asActor(fixer,"FIXER",()->jdbc.update("INSERT INTO payout_entries(earning_id,payout_id,fixer_user_id,net_amount) VALUES (?,?,?,90000)",paid,p,fixer)))
                .hasStackTraceContaining("Payout entry requires an available earning");
        assertThat(asActor(fixer,"FIXER",()->jdbc.update("DELETE FROM payout_entries"))).isZero();
        assertThat(asActor(fixer,"FIXER",()->jdbc.update("DELETE FROM payouts"))).isZero();
        assertThat(count("payout_entries")).isEqualTo(2);
    }
    private void completeCurrentJob() throws Exception {
        UUID job=jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id=?",UUID.class,quotation);
        mvc.perform(post("/jobs/"+job+"/complete").with(identity("fixer")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
    private void anotherApprovedService() throws Exception {
        request=uuid(json(mvc.perform(post("/requests").with(identity("owner")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\""+property+"\",\"title\":\"Fuga\",\"description\":\"Reparar tuberia\"}"))
                .andExpect(status().isCreated()).andReturn()),"requestId");
        jdbc.update("UPDATE repair_requests SET specialty='PLUMBING' WHERE id=?",request);
        quotation=uuid(json(mvc.perform(post("/quotations").with(identity("fixer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\""+request+"\",\"amount\":100000,\"estimatedDays\":2,\"message\":\"Reparacion\"}"))
                .andExpect(status().isCreated()).andReturn()),"id");
        payment=uuid(json(mvc.perform(post("/quotations/"+quotation+"/accept").with(identity("owner"))).andExpect(status().isOk()).andReturn()),"paymentId");
        reference=jdbc.queryForObject("SELECT reference FROM repair_payments WHERE id=?",String.class,payment);
        pay("owner",UUID.randomUUID(),"SANDBOX_CARD").andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(strings={"TENANT","REAL_ESTATE_MANAGER"})
    void delegatedCreationPreservesCanonicalOwnerAndCannotEvadeHistoricalDebt(String role) throws Exception {
        accepted(true,"CASH");
        String actor=role.equals("TENANT")?"tenant":"manager";
        UUID actorId=role.equals("TENANT")?provision(actor,role):manager;
        if(role.equals("TENANT")) jdbc.update("INSERT INTO rental_contracts(id,property_id,owner_user_id,tenant_user_id,start_date,end_date,monthly_rent_amount,deposit_amount,payment_day_of_month,status,created_at,updated_at) VALUES (?,?,?,?,CURRENT_DATE-1,CURRENT_DATE+30,1000000,0,5,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID(),property,owner,actorId);
        String body="{\"propertyId\":\""+property+"\",\"title\":\"Fuga\",\"description\":\"Reparar tuberia\",\"urgencyLevel\":\"URGENT\"}";
        mvc.perform(post("/requests").with(identity(actor)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.paymentMethod").value("TRANSFER"))
                .andExpect(jsonPath("$.ownerUserId").value(owner.toString()))
                .andExpect(jsonPath("$.requestedByUserId").value(actorId.toString()))
                .andExpect(jsonPath("$.managerUserId").value(manager.toString()))
                .andExpect(jsonPath("$.slaDeadline").isNotEmpty());
        UUID job=jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id=?",UUID.class,quotation);
        mvc.perform(post("/jobs/"+job+"/complete").with(identity("fixer")).contentType(MediaType.APPLICATION_JSON).content("{\"receivedAmount\":60000}"))
                .andExpect(status().isOk());
        int before=count("repair_requests");
        mvc.perform(post("/requests").with(identity(actor)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_HAS_OUTSTANDING_DEBT"));
        assertThat(count("repair_requests")).isEqualTo(before);
    }

    private void accepted(boolean byManager, String method) throws Exception {
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
        if (method.equals("CASH")) {
            com.fixup.testsupport.HistoricalCashFixtures.acceptedJob(jdbc, quotation);
            return;
        }
        var result = json(mvc.perform(post("/quotations/" + quotation + "/accept").with(identity(byManager ? "manager" : "owner")))
                .andExpect(status().isOk()).andReturn());
        if (method.equals("TRANSFER")) {
            payment = uuid(result, "paymentId");
            reference = jdbc.queryForObject("SELECT reference FROM repair_payments WHERE id = ?", String.class, payment);
        }
    }
    private UUID provision(String name, String role) throws Exception {
        UUID id = uuid(json(mvc.perform(post("/auth/bootstrap").with(identity(name))).andExpect(status().isCreated()).andReturn()), "id");
        if (role.equals("REAL_ESTATE_MANAGER") || role.equals("PLATFORM_ADMIN")) {
            // Administrative role is provisioned by trusted fixture setup, never self-selected by HTTP.
            jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
            return id;
        }
        mvc.perform(post("/auth/select-role").with(identity(name)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        return id;
    }
    private RequestPostProcessor identity(String name) { return jwt().jwt(t -> t.subject("auth0|digital-" + name).claim("email", name + "@example.test").claim("name", name)); }
    private JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString()); }
    private UUID uuid(JsonNode j, String key) { return UUID.fromString(j.get(key).asText()); }
    private String body(String ref, String amount, String instrument) { return "{\"reference\":\"" + ref + "\",\"amount\":" + amount + ",\"instrument\":\"" + instrument + "\"}"; }
    private MockHttpServletRequestBuilder attempt(String actor, UUID key, String body) {
        return post("/payments/" + payment + "/attempts").with(identity(actor)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private ResultActions pay(String actor, UUID key, String instrument) throws Exception { return mvc.perform(attempt(actor, key, body(reference, "100000", instrument))); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private void assertApprovedOnce() {
        assertThat(count("jobs")).isEqualTo(1); assertThat(count("job_status_log")).isEqualTo(1); assertThat(count("fixer_earnings")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM fixer_earnings", String.class)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT commission_amount FROM fixer_earnings", Long.class)).isEqualTo(10000L);
    }
    private Map<String, List<Map<String, Object>>> financialSnapshot() {
        var snapshot = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("repair_payments", "simulated_payment_attempts", "fixer_earnings", "jobs", "job_status_log", "notifications"))
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return snapshot;
    }
    private MockHttpServletRequestBuilder reportRequest(String actor) {
        var today = LocalDate.now(java.time.ZoneId.of("America/Bogota"));
        return get("/analytics/expenses").with(identity(actor)).param("from", today.minusDays(1).toString()).param("to", today.toString());
    }
    private void report(String actor, long total, int count) throws Exception {
        mvc.perform(reportRequest(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(total)).andExpect(jsonPath("$.count").value(count));
    }
    private <T> T asActor(UUID id, String role, Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(s -> {
            jdbc.execute("SET LOCAL ROLE fixup_app");
            jdbc.queryForObject("SELECT set_config('app.current_user_id', ?, true)", String.class, id.toString());
            jdbc.queryForObject("SELECT set_config('app.current_user_roles', ?, true)", String.class, role);
            return action.get();
        });
    }
}
