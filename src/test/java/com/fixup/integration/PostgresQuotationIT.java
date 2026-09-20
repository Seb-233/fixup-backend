package com.fixup.integration;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class, QuotationHttpContract.EventRecorder.class})
@Testcontainers
class PostgresQuotationIT extends QuotationHttpContract {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * FR-UC-25: overridden because Row-Level Security only exists on PostgreSQL. There, a stranger's
     * SELECT never returns the row at all, so GetRepairRequest never gets a row to reject with 403 --
     * it sees NOT_FOUND, exactly as if the request never existed. That is a stricter guarantee than
     * the H2 profile's 403 (it also never confirms the resource's existence to an unrelated caller),
     * so the assertion differs here instead of weakening it back to 403 for both databases.
     */
    @Test
    @Override
    void strangerCannotReadDetail() throws Exception {
        String ownerSubject = "auth0|owner-secret";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Gotera baño", "Reparación requerida con urgencia");

        String strangerSubject = "auth0|stranger-user";
        provision(strangerSubject); // Authenticated user with no special role

        mvc.perform(get("/requests/" + requestId).with(identity(strangerSubject)))
                .andExpect(status().isNotFound());
    }

    /**
     * FR-UC-25: overridden for the same reason as strangerCannotReadDetail. Owner B has no
     * relationship at all to Owner A's quotation (not the fixer who sent it, not the owner of the
     * request it answers), so RLS hides the row before AcceptQuotation/RejectQuotation ever get a
     * chance to load it and answer with 403 -- it is NOT_FOUND, same as the H2 profile's ACCESS_DENIED
     * is at least as strong a guarantee: the write is still refused either way.
     */
    @Test
    @Override
    void quotationOfAnotherOwnerReturns403() throws Exception {
        String ownerASubject = "auth0|owner-a";
        provisionOwner(ownerASubject);
        UUID requestId = createRequest(ownerASubject, "PLUMBING", "Llave goteando", "Cambio de empaque");

        String fixerSubject = "auth0|fixer-offerer";
        provisionVerifiedFixer(fixerSubject, "PLUMBING");
        UUID quotationId = submitQuotation(fixerSubject, requestId, 80_000L, 1, "Puedo ir hoy");

        String ownerBSubject = "auth0|owner-b";
        provisionOwner(ownerBSubject);

        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerBSubject)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTATION_NOT_FOUND"));

        mvc.perform(post("/quotations/" + quotationId + "/reject").with(identity(ownerBSubject)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTATION_NOT_FOUND"));
    }

    /**
     * FR-UC-25: overridden for the same reason. Fixer B has no relationship at all to the request
     * once it is ASSIGNED to someone else (not the assigned fixer, no quotation of their own on it,
     * and it is no longer OPEN), so RLS hides it before SubmitQuotation can see its status and answer
     * with 409 REQUEST_NOT_OPEN -- it is NOT_FOUND instead, which still refuses the submission.
     */
    @Test
    @Override
    void submittingQuotationForNonOpenRequestReturnsConflict() throws Exception {
        String ownerSubject = "auth0|owner-submit-closed";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Tubo roto", "Fuga en cocina");

        String fixerASubject = "auth0|fixer-first-compat";
        provisionVerifiedFixer(fixerASubject, "PLUMBING");
        UUID quotationId = submitQuotation(fixerASubject, requestId, 80000L, 1, "Oferta ganadora");

        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerSubject)))
                .andExpect(status().isOk());

        String fixerBSubject = "auth0|fixer-second-compat";
        provisionVerifiedFixer(fixerBSubject, "PLUMBING");

        String body = """
                {
                    "requestId": "%s",
                    "amount": 90000,
                    "estimatedDays": 2,
                    "message": "Llegué tarde"
                }
                """.formatted(requestId);

        mvc.perform(post("/quotations").with(identity(fixerBSubject))
                .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    /**
     * FR-UC-25: overridden for the same reason. When Fixer B's submission lands after the request is
     * already ASSIGNED to Fixer A, Fixer B has no relationship to it (no quotation of their own on it
     * yet), so RLS hides the row instead of letting SubmitQuotation see the ASSIGNED status and answer
     * with 409 REQUEST_NOT_OPEN -- the losing side of the race is NOT_FOUND (404) instead of 409, the
     * winning side is unchanged (201).
     */
    @Test
    @Override
    void concurrentQuotationSubmissionAndAcceptanceLeavesConsistentState() throws Exception {
        String ownerSubject = "auth0|owner-concurrent-sub-acc";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Tubería rota", "Fuga en baño principal");

        String fixerASubject = "auth0|fixer-concurrent-sub-acc-a";
        provisionVerifiedFixer(fixerASubject, "PLUMBING");
        UUID quotationAId = submitQuotation(fixerASubject, requestId, 200_000L, 2, "Oferta Fixer A");

        String fixerBSubject = "auth0|fixer-concurrent-sub-acc-b";
        provisionVerifiedFixer(fixerBSubject, "PLUMBING");

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var acceptStatus = new AtomicInteger(0);
        var submitStatus = new AtomicInteger(0);

        String quotationBBody = """
                {
                    "requestId": "%s",
                    "amount": 250000,
                    "estimatedDays": 3,
                    "message": "Oferta concurrente Fixer B"
                }
                """.formatted(requestId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var taskAccept = (Callable<Void>) () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                var result = mvc.perform(post("/quotations/" + quotationAId + "/accept").with(identity(ownerSubject)))
                        .andReturn();
                acceptStatus.set(result.getResponse().getStatus());
                return null;
            };

            var taskSubmit = (Callable<Void>) () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                var result = mvc.perform(post("/quotations").with(identity(fixerBSubject))
                        .contentType(APPLICATION_JSON).content(quotationBBody))
                        .andReturn();
                submitStatus.set(result.getResponse().getStatus());
                return null;
            };

            var futures = List.of(executor.submit(taskAccept), executor.submit(taskSubmit));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        }

        assertThat(acceptStatus.get()).as("acceptStatus must not be 500").isNotEqualTo(500);
        assertThat(submitStatus.get()).as("submitStatus must not be 500").isNotEqualTo(500);

        assertThat(acceptStatus.get()).isEqualTo(200);
        assertThat(submitStatus.get()).isIn(201, 404);

        Integer acceptedCount = jdbc.queryForObject(
                "SELECT count(*) FROM quotations WHERE request_id = ? AND status = 'ACCEPTED'", Integer.class, requestId);
        Integer submittedCount = jdbc.queryForObject(
                "SELECT count(*) FROM quotations WHERE request_id = ? AND status = 'SUBMITTED'", Integer.class, requestId);
        Integer totalCount = jdbc.queryForObject(
                "SELECT count(*) FROM quotations WHERE request_id = ?", Integer.class, requestId);
        Integer rejectedCount = jdbc.queryForObject(
                "SELECT count(*) FROM quotations WHERE request_id = ? AND status = 'REJECTED'", Integer.class, requestId);
        String requestStatus = jdbc.queryForObject(
                "SELECT status FROM repair_requests WHERE id = ?", String.class, requestId);

        assertThat(acceptedCount).isEqualTo(1);
        assertThat(submittedCount).isEqualTo(0);
        assertThat(rejectedCount).isEqualTo(totalCount - 1);
        assertThat(requestStatus).isEqualTo("ASSIGNED");
    }
}
