package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.jobs.api.JobCompleted;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationStatus;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.api.PushNotificationGateway;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import com.fixup.testsupport.PropertyFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real transactions and RLS: failures are injected only in this disposable database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@RecordApplicationEvents
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class PostgresJobCompletionIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IntegrationDatabaseCleaner cleaner;
    @Autowired ApplicationEvents events;
    @MockitoBean PushNotificationGateway push;

    @BeforeEach
    void prepare() {
        cleaner.clean();
        when(push.send(any())).thenReturn(NotificationStatus.SKIPPED);
    }

    @AfterEach
    void cleanup() {
        // Only objects in this class's private, disposable PostgreSQL container.
        jdbc.execute("DROP TRIGGER IF EXISTS fr052_fail_completion ON fixer_earnings");
        jdbc.execute("DROP TRIGGER IF EXISTS fr052_fail_completion ON repair_requests");
        jdbc.execute("DROP FUNCTION IF EXISTS fr052_fail_completion()");
        cleaner.clean();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASSIGNED", "SLA_WARNING", "SLA_BREACHED"})
    void closesAndReleasesExactlyOnceWithOneTimestamp(String requestStatus) throws Exception {
        var f = fundedJob();
        jdbc.update("UPDATE repair_requests SET status = ? WHERE id = ?", requestStatus, f.requestId());
        mvc.perform(close(f)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?", String.class, f.requestId()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs j JOIN repair_requests r ON r.id = j.request_id "
                + "JOIN fixer_earnings e ON e.quotation_id = j.quotation_id "
                + "JOIN job_status_log l ON l.job_id = j.id AND l.status = 'COMPLETED' "
                + "JOIN property_work_history h ON h.job_id = j.id "
                + "WHERE j.id = ? AND j.status = 'COMPLETED' AND e.status = 'AVAILABLE' "
                + "AND j.completed_at = r.completed_at AND j.completed_at = e.released_at "
                + "AND j.completed_at = l.changed_at AND j.completed_at = h.completed_at "
                + "AND e.gross_amount = 100000 AND e.commission_amount = 10000 AND e.net_amount = 90000",
                Integer.class, f.jobId())).isEqualTo(1);
        assertThat(events.stream(JobCompleted.class)).hasSize(1);
        var beforeRepeat = snapshot();
        clearInvocations(push);
        mvc.perform(close(f)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_ALREADY_COMPLETED"));
        assertThat(snapshot()).isEqualTo(beforeRepeat);
        assertThat(events.stream(JobCompleted.class)).hasSize(1);
        verifyNoInteractions(push);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixer_earnings", "repair_requests"})
    void sqlFailureRollsBackJobLogRequestAndFinancialWrites(String table) throws Exception {
        var f = fundedJob();
        var before = snapshot();
        clearInvocations(push);
        // AFTER UPDATE proves a failing financial write, or a failing request write after release.
        jdbc.execute("""
                CREATE FUNCTION fr052_fail_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.status IN ('AVAILABLE', 'COMPLETED') THEN
                        RAISE EXCEPTION 'FR052_INJECTED_SQL_FAILURE';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER fr052_fail_completion AFTER UPDATE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION fr052_fail_completion()");
        assertThatThrownBy(() -> mvc.perform(close(f))).hasStackTraceContaining("FR052_INJECTED_SQL_FAILURE");
        assertThat(snapshot()).isEqualTo(before);
        assertNoCompletionEffects();
    }

    @Test
    void cancelledRequestRollsBackTheEarlierReleaseAndJobWrites() throws Exception {
        var f = fundedJob();
        jdbc.update("UPDATE repair_requests SET status = 'CANCELLED' WHERE id = ?", f.requestId());
        var before = snapshot();
        clearInvocations(push);
        mvc.perform(close(f)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_NOT_ASSIGNED"));
        assertThat(snapshot()).isEqualTo(before);
        assertNoCompletionEffects();
    }

    @Test
    void ownerMismatchRollsBackTheEarlierRelease() throws Exception {
        var f = fundedJob();
        UUID otherOwner = provision("auth0|completion-other-owner", "OWNER");
        jdbc.update("UPDATE jobs SET owner_user_id = ? WHERE id = ?", otherOwner, f.jobId());
        var before = snapshot();
        clearInvocations(push);
        mvc.perform(close(f)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_NOT_ASSIGNED"));
        assertThat(snapshot()).isEqualTo(before);
        assertNoCompletionEffects();
    }

    @Test
    void wrongAssignedFixerCannotCloseTheRequestAndNoEffectsSurvive() throws Exception {
        var f = fundedJob();
        UUID other = provision("auth0|completion-other-fixer", "FIXER");
        jdbc.update("UPDATE repair_requests SET assigned_fixer_user_id = ? WHERE id = ?", other, f.requestId());
        var before = snapshot();
        clearInvocations(push);
        // RLS may hide the mismatched request before the explicit identity check.
        mvc.perform(close(f)).andExpect(status().is4xxClientError());
        assertThat(snapshot()).isEqualTo(before);
        assertNoCompletionEffects();
    }

    @Test
    void existingAvailableEarningKeepsItsAmountsAndReleaseTimestamp() throws Exception {
        var f = fundedJob();
        jdbc.update("UPDATE fixer_earnings SET status = 'AVAILABLE', released_at = CURRENT_TIMESTAMP "
                + "WHERE quotation_id = ?", f.quotationId());
        var earningBefore = jdbc.queryForList("SELECT * FROM fixer_earnings ORDER BY id");
        mvc.perform(close(f)).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT * FROM fixer_earnings ORDER BY id")).isEqualTo(earningBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_status_log WHERE job_id = ? AND status = 'COMPLETED'",
                Integer.class, f.jobId())).isEqualTo(1);
    }

    private void assertNoCompletionEffects() {
        assertThat(events.stream(JobCompleted.class)).isEmpty();
        assertThat(events.stream(NotificationRequested.class)
                .filter(event -> event.type() == NotificationType.JOB_COMPLETED)).isEmpty();
        verifyNoInteractions(push);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("jobs", "job_status_log", "repair_requests", "fixer_earnings",
                "cash_settlements", "owner_debts", "repair_payments", "property_work_history",
                "job_evidences", "notifications", "technical_visits")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }

    private Fixture fundedJob() throws Exception {
        String owner = "auth0|completion-owner", fixer = "auth0|completion-fixer";
        UUID ownerId = provision(owner, "OWNER"), fixerId = provision(fixer, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixerId);
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, 'PLUMBING')", fixerId);
        UUID property = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, property, ownerId);
        UUID request = id(mvc.perform(post("/requests").with(identity(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"propertyId":"%s","title":"Fuga de agua","description":"Reparar tuberia principal",
                         "mediaIds":[],"paymentMethod":"TRANSFER"}
                        """.formatted(property))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "requestId");
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", request);
        UUID quotation = id(mvc.perform(post("/quotations").with(identity(fixer)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","amount":100000,"estimatedDays":2,"message":"Materiales incluidos"}
                        """.formatted(request))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        UUID payment = id(mvc.perform(post("/quotations/" + quotation + "/accept").with(identity(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "paymentId");
        mvc.perform(post("/payments/" + payment + "/simulate").with(identity(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"APPROVED\"}")).andExpect(status().isOk());
        UUID job = jdbc.queryForObject("SELECT id FROM jobs WHERE quotation_id = ?", UUID.class, quotation);
        return new Fixture(job, request, quotation, fixer);
    }

    private UUID provision(String subject, String role) throws Exception {
        UUID user = id(mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        mvc.perform(post("/auth/select-role").with(identity(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}")).andExpect(status().isOk());
        return user;
    }

    private UUID id(String json, String field) throws Exception {
        return UUID.fromString(mapper.readTree(json).get(field).asText());
    }

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test").claim("name", "Test"));
    }

    private MockHttpServletRequestBuilder close(Fixture f) {
        return post("/jobs/" + f.jobId() + "/complete").with(identity(f.fixerSubject()));
    }

    private record Fixture(UUID jobId, UUID requestId, UUID quotationId, String fixerSubject) {}
}
