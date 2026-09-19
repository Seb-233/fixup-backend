package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.quotations.api.QuotationAccepted;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FR-UC-18: HTTP and security contract covering quotation and request workflows.
 * Reused unchanged against H2 and PostgreSQL.
 */
abstract class QuotationHttpContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;
    @Autowired EventRecorder events;

    public static class EventRecorder {
        final List<QuotationAccepted> acceptedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();

        @EventListener
        void on(QuotationAccepted event) {
            acceptedEvents.add(event);
        }

        void clear() {
            acceptedEvents.clear();
        }
    }

    @BeforeEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        events.clear();
        databaseCleaner.clean();
    }

    // ---------- helpers ----------

    RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }

    UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    UUID provisionOwner(String subject) throws Exception {
        UUID id = provision(subject);
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());
        return id;
    }

    UUID provisionFixer(String subject) throws Exception {
        UUID id = provision(subject);
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                .andExpect(status().isOk());
        return id;
    }

    UUID provisionVerifiedFixer(String subject) throws Exception {
        UUID fixerId = provisionFixer(subject);
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixerId);
        return fixerId;
    }

    UUID provisionVerifiedFixer(String subject, String... specialties) throws Exception {
        UUID fixerId = provisionVerifiedFixer(subject);
        for (String specialty : specialties) {
            addFixerSpecialty(fixerId, specialty);
        }
        return fixerId;
    }

    void addFixerSpecialty(UUID fixerUserId, String specialty) {
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, ?)", fixerUserId, specialty);
    }

    UUID provisionAdmin(String subject) throws Exception {
        UUID id = provision(subject);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", id);
        return id;
    }

    UUID createRequest(String ownerSubject, String specialty, String title, String description) throws Exception {
        String body = """
                {
                    "specialty": "%s",
                    "title": "%s",
                    "description": "%s",
                    "photoKeys": ["photos/sample.jpg"]
                }
                """.formatted(specialty, title, description);
        var result = mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("requestId").asText());
    }

    UUID submitQuotation(String fixerSubject, UUID requestId, long amount, int days, String message) throws Exception {
        String body = """
                {
                    "requestId": "%s",
                    "amount": %d,
                    "estimatedDays": %d,
                    "message": "%s"
                }
                """.formatted(requestId, amount, days, message);
        var result = mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // ---------- Tests ----------

    @Test
    void unverifiedFixerCannotReadInboxOrDetail() throws Exception {
        String ownerSubject = "auth0|owner-test";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Tubo roto", "Fuga constante de agua en cocina");

        String fixerSubject = "auth0|fixer-pending";
        provisionFixer(fixerSubject); // registered as FIXER, but pending verification

        // GET /requests/open must return 403 for unverified fixer
        mvc.perform(get("/requests/open").with(identity(fixerSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // GET /requests/{requestId} must return 403 for unverified fixer
        mvc.perform(get("/requests/" + requestId).with(identity(fixerSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void incompatibleFixerDoesNotReceiveNorReadRequest() throws Exception {
        String ownerSubject = "auth0|owner-test-specialty";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Tubo roto", "Fuga constante de agua en cocina");

        String fixerSubject = "auth0|fixer-electrical";
        provisionVerifiedFixer(fixerSubject, "ELECTRICAL");

        // Inbox should not list the PLUMBING request
        mvc.perform(get("/requests/open").with(identity(fixerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Detail request for incompatible specialty must return 403
        mvc.perform(get("/requests/" + requestId).with(identity(fixerSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void compatibleVerifiedFixerCanListAndReadDetail() throws Exception {
        String ownerSubject = "auth0|owner-test-compat";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Tubo roto", "Fuga constante de agua en cocina");

        String fixerSubject = "auth0|fixer-plumber";
        provisionVerifiedFixer(fixerSubject, "PLUMBING", "GENERAL");

        // Inbox contains the compatible request
        mvc.perform(get("/requests/open").with(identity(fixerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].specialty").value("PLUMBING"))
                .andExpect(jsonPath("$[0].title").value("Tubo roto"));

        // Detail returns 200 with complete info
        mvc.perform(get("/requests/" + requestId).with(identity(fixerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.specialty").value("PLUMBING"))
                .andExpect(jsonPath("$.description").value("Fuga constante de agua en cocina"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void strangerCannotReadDetail() throws Exception {
        String ownerSubject = "auth0|owner-secret";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Gotera baño", "Reparación requerida con urgencia");

        String strangerSubject = "auth0|stranger-user";
        provision(strangerSubject); // Authenticated user with no special role

        mvc.perform(get("/requests/" + requestId).with(identity(strangerSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void ownerCanQueryOwnResources() throws Exception {
        String ownerSubject = "auth0|owner-own-resources";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PAINTING", "Pintar fachada", "Pintura exterior de dos pisos");

        // GET /requests/me
        mvc.perform(get("/requests/me").with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].title").value("Pintar fachada"));

        // GET /requests/{requestId}
        mvc.perform(get("/requests/" + requestId).with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.title").value("Pintar fachada"));
    }

    @Test
    void privateFieldsAbsentInSummaryResponse() throws Exception {
        String ownerSubject = "auth0|owner-privacy";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "CARPENTRY", "Puerta de roble", "Ajustar marco y cerradura");

        String fixerSubject = "auth0|fixer-privacy";
        provisionVerifiedFixer(fixerSubject, "CARPENTRY");

        mvc.perform(get("/requests/open").with(identity(fixerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].specialty").value("CARPENTRY"))
                .andExpect(jsonPath("$[0].title").value("Puerta de roble"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                // Verify private fields are strictly absent
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$[0].description").doesNotExist())
                .andExpect(jsonPath("$[0].photoKeys").doesNotExist())
                .andExpect(jsonPath("$[0].assignedFixerUserId").doesNotExist());
    }

    @Test
    void quotationOfAnotherOwnerReturns403() throws Exception {
        String ownerASubject = "auth0|owner-a";
        provisionOwner(ownerASubject);
        UUID requestId = createRequest(ownerASubject, "PLUMBING", "Llave goteando", "Cambio de empaque");

        String fixerSubject = "auth0|fixer-offerer";
        provisionVerifiedFixer(fixerSubject, "PLUMBING");
        UUID quotationId = submitQuotation(fixerSubject, requestId, 80_000L, 1, "Puedo ir hoy");

        String ownerBSubject = "auth0|owner-b";
        provisionOwner(ownerBSubject);

        // Owner B tries to accept Owner A's quotation -> 403
        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerBSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Owner B tries to reject Owner A's quotation -> 403
        mvc.perform(post("/quotations/" + quotationId + "/reject").with(identity(ownerBSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void explicitRejectionByOwnerTransitionsToRejected() throws Exception {
        String ownerSubject = "auth0|owner-reject-test";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "ELECTRICAL", "Toma corriente", "No hay energía");

        String fixerSubject = "auth0|fixer-rej";
        provisionVerifiedFixer(fixerSubject, "ELECTRICAL");
        UUID quotationId = submitQuotation(fixerSubject, requestId, 120_000L, 2, "Revisión general");

        // Owner explicitly rejects the quotation
        mvc.perform(post("/quotations/" + quotationId + "/reject").with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(quotationId.toString()))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Rejecting again returns 409
        mvc.perform(post("/quotations/" + quotationId + "/reject").with(identity(ownerSubject)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTATION_NOT_SUBMITTED"));
    }

    @Test
    void coherentAcceptanceEventPublished() throws Exception {
        String ownerSubject = "auth0|owner-event";
        UUID ownerId = provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "MASONRY", "Muro agrietado", "Resanar grieta");

        String fixerSubject = "auth0|fixer-event";
        UUID fixerId = provisionVerifiedFixer(fixerSubject, "MASONRY");
        UUID quotationId = submitQuotation(fixerSubject, requestId, 300_000L, 4, "Materiales incluidos");

        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(events.acceptedEvents).hasSize(1);
        QuotationAccepted event = events.acceptedEvents.getFirst();
        assertThat(event.quotationId()).isEqualTo(quotationId);
        assertThat(event.requestId()).isEqualTo(requestId);
        assertThat(event.ownerUserId()).isEqualTo(ownerId);
        assertThat(event.fixerUserId()).isEqualTo(fixerId);
        assertThat(event.amount()).isEqualTo(300_000L);
    }

    @Test
    void twoSimultaneousAcceptancesOnlyOneWins() throws Exception {
        String ownerSubject = "auth0|owner-concurrent-accept";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Tubería principal", "Fuga en ducto de entrada");

        String fixerASubject = "auth0|fixer-concurrent-a";
        provisionVerifiedFixer(fixerASubject, "PLUMBING");
        UUID quotationAId = submitQuotation(fixerASubject, requestId, 200_000L, 2, "Oferta Fixer A");

        String fixerBSubject = "auth0|fixer-concurrent-b";
        provisionVerifiedFixer(fixerBSubject, "PLUMBING");
        UUID quotationBId = submitQuotation(fixerBSubject, requestId, 220_000L, 3, "Oferta Fixer B");

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var successCount = new AtomicInteger(0);
        var conflictCount = new AtomicInteger(0);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var taskA = (Callable<Void>) () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                var result = mvc.perform(post("/quotations/" + quotationAId + "/accept").with(identity(ownerSubject)))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                if (status == 409) conflictCount.incrementAndGet();
                return null;
            };

            var taskB = (Callable<Void>) () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                var result = mvc.perform(post("/quotations/" + quotationBId + "/accept").with(identity(ownerSubject)))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                if (status == 409) conflictCount.incrementAndGet();
                return null;
            };

            var futures = List.of(executor.submit(taskA), executor.submit(taskB));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        }

        // Exactly one acceptance must succeed, and the other must be rejected with 409
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Verify database state: one accepted, one rejected, request assigned
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quotations WHERE status = 'ACCEPTED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quotations WHERE status = 'REJECTED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?", String.class, requestId))
                .isEqualTo("ASSIGNED");
    }

    @Test
    void concurrentDuplicateQuotationGets409Never500() throws Exception {
        String ownerSubject = "auth0|owner-duplicate-test";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "ELECTRICAL", "Cortocircuito", "Chispas en caja de fusibles");

        String fixerSubject = "auth0|fixer-duplicate";
        provisionVerifiedFixer(fixerSubject, "ELECTRICAL");

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var createdCount = new AtomicInteger(0);
        var conflictCount = new AtomicInteger(0);
        var otherCount = new AtomicInteger(0);

        String body = """
                {
                    "requestId": "%s",
                    "amount": 150000,
                    "estimatedDays": 2,
                    "message": "Arreglo garantizado"
                }
                """.formatted(requestId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var task1 = (Callable<Void>) () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                var result = mvc.perform(post("/quotations").with(identity(fixerSubject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 201) createdCount.incrementAndGet();
                else if (status == 409) conflictCount.incrementAndGet();
                else otherCount.incrementAndGet();
                return null;
            };

            var task2 = (Callable<Void>) () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                var result = mvc.perform(post("/quotations").with(identity(fixerSubject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
                int status = result.getResponse().getStatus();
                if (status == 201) createdCount.incrementAndGet();
                else if (status == 409) conflictCount.incrementAndGet();
                else otherCount.incrementAndGet();
                return null;
            };

            var futures = List.of(executor.submit(task1), executor.submit(task2));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        }

        // Never 500: one must succeed with 201, the other must fail with 409 ALREADY_QUOTED
        assertThat(otherCount.get()).isZero();
        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quotations WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }

    @Test
    void verifiedCompatibleFixerCanSubmitQuotation() throws Exception {
        String ownerSubject = "auth0|owner-submit-compat";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Fuga lavamanos", "Gotea bajo el sifón");

        String fixerSubject = "auth0|fixer-submit-compat";
        UUID fixerId = provisionVerifiedFixer(fixerSubject, "PLUMBING");

        String body = """
                {
                    "requestId": "%s",
                    "amount": 95000,
                    "estimatedDays": 1,
                    "message": "Puedo atender hoy mismo"
                }
                """.formatted(requestId);

        mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.fixerUserId").value(fixerId.toString()))
                .andExpect(jsonPath("$.amount").value(95000))
                .andExpect(jsonPath("$.estimatedDays").value(1))
                .andExpect(jsonPath("$.message").value("Puedo atender hoy mismo"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void verifiedIncompatibleFixerCannotSubmitQuotation() throws Exception {
        String ownerSubject = "auth0|owner-submit-incompat";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Fuga lavamanos", "Gotea bajo el sifón");

        String fixerSubject = "auth0|fixer-submit-incompat";
        provisionVerifiedFixer(fixerSubject, "CARPENTRY"); // Only carpentry, request is PLUMBING

        String body = """
                {
                    "requestId": "%s",
                    "amount": 95000,
                    "estimatedDays": 1,
                    "message": "Intento de cotizar oficio incompatible"
                }
                """.formatted(requestId);

        var result = mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertThat(content).doesNotContain("ownerUserId")
                .doesNotContain("Fuga lavamanos")
                .doesNotContain("Gotea bajo el sifón")
                .doesNotContain("photoKeys")
                .doesNotContain("photos/sample.jpg");
    }

    @Test
    void unverifiedFixerCannotSubmitQuotation() throws Exception {
        String ownerSubject = "auth0|owner-submit-unverified";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "ELECTRICAL", "Tablero chispeando", "Revisar breaker principal");

        String fixerSubject = "auth0|fixer-submit-unverified";
        provisionFixer(fixerSubject); // Has FIXER role but is pending verification

        String body = """
                {
                    "requestId": "%s",
                    "amount": 100000,
                    "estimatedDays": 1,
                    "message": "Soy electricista aún no verificado"
                }
                """.formatted(requestId);

        mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void userWithoutFixerRoleCannotSubmitQuotation() throws Exception {
        String ownerSubject = "auth0|owner-submit-norole";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "ELECTRICAL", "Tablero chispeando", "Revisar breaker principal");

        String nonFixerSubject = "auth0|other-owner-quoting";
        provisionOwner(nonFixerSubject);

        String body = """
                {
                    "requestId": "%s",
                    "amount": 100000,
                    "estimatedDays": 1,
                    "message": "Propietario intentando cotizar"
                }
                """.formatted(requestId);

        mvc.perform(post("/quotations").with(identity(nonFixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void submittingQuotationForNonExistentRequestReturnsNotFound() throws Exception {
        String fixerSubject = "auth0|fixer-submit-404";
        provisionVerifiedFixer(fixerSubject, "ELECTRICAL");
        UUID fakeRequestId = UUID.randomUUID();

        String body = """
                {
                    "requestId": "%s",
                    "amount": 100000,
                    "estimatedDays": 1,
                    "message": "Cotización a solicitud inexistente"
                }
                """.formatted(fakeRequestId);

        mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    @Test
    void submittingQuotationForNonOpenRequestReturnsConflict() throws Exception {
        String ownerSubject = "auth0|owner-submit-closed";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Tubo roto", "Fuga en cocina");

        String fixerASubject = "auth0|fixer-first-compat";
        provisionVerifiedFixer(fixerASubject, "PLUMBING");
        UUID quotationId = submitQuotation(fixerASubject, requestId, 80000L, 1, "Oferta ganadora");

        // Owner accepts quotation -> request becomes ASSIGNED (not OPEN)
        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerSubject)))
                .andExpect(status().isOk());

        // Fixer B tries to submit quotation for now-assigned request -> 409 REQUEST_NOT_OPEN
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
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_OPEN"));
    }

    @Test
    void secondQuotationFromSameFixerReturnsConflict() throws Exception {
        String ownerSubject = "auth0|owner-submit-twice";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PAINTING", "Pintar sala", "Pintura vinilo");

        String fixerSubject = "auth0|fixer-submit-twice";
        provisionVerifiedFixer(fixerSubject, "PAINTING");

        submitQuotation(fixerSubject, requestId, 120000L, 2, "Primera oferta");

        String body = """
                {
                    "requestId": "%s",
                    "amount": 110000,
                    "estimatedDays": 2,
                    "message": "Segunda oferta para mejorar precio"
                }
                """.formatted(requestId);

        mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_QUOTED"));
    }
}
