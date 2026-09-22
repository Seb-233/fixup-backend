package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.quotations.domain.Quotation;
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

    protected abstract org.springframework.test.web.servlet.ResultMatcher expectedDenialStatus();


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
        TestStorageConfiguration.instance().clear();
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
        return createRequestWithMedia(ownerSubject, specialty, title, description, List.of());
    }

    UUID createRequestWithMedia(String ownerSubject, String specialty, String title, String description,
            List<UUID> mediaIds) throws Exception {
        String mediaArray = mapper.writeValueAsString(mediaIds);
        java.util.UUID reqPropId = java.util.UUID.randomUUID();
        java.util.UUID ownerUserId = java.util.UUID.fromString(mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(ownerSubject)))).andReturn().getResponse().getContentAsString()).get("id").asText());
        jdbc.update("INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", reqPropId, ownerUserId);
        String body = "{\"propertyId\":\"" + reqPropId.toString() + "\",\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"mediaIds\":" + mediaArray + "}";
        var result = mvc.perform(post("/requests").with(identity(ownerSubject)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        java.util.UUID reqId = java.util.UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("requestId").asText());
        jdbc.update("UPDATE repair_requests SET specialty = ? WHERE id = ?", specialty, reqId);
        return reqId;
    }

    UUID uploadAndConfirmRepairRequestMedia(String ownerSubject, UUID ownerUserId) throws Exception {
        UUID mediaId = UUID.randomUUID();
        String objectKey = "requests/" + ownerUserId + "/" + mediaId + ".jpg";
        TestStorageConfiguration.instance().put(objectKey, TestStorageConfiguration.JPEG_MAGIC, "image/jpeg");
        jdbc.update("INSERT INTO media_assets (id, owner_user_id, purpose, object_key, content_type, size_bytes, status, upload_expires_at, confirmed_at, created_at) "
                + "VALUES (?, ?, 'REPAIR_REQUEST', ?, 'image/jpeg', 100, 'READY', CURRENT_TIMESTAMP + INTERVAL '1' DAY, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                mediaId, ownerUserId, objectKey);
        return mediaId;
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
                .andExpect(expectedDenialStatus());
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
                .andExpect(expectedDenialStatus());
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
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.photos").isArray());
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
                .andExpect(jsonPath("$[0].photos").doesNotExist())
                .andExpect(jsonPath("$[0].photoKeys").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$[0].mediaIds").doesNotExist())
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
                .andExpect(expectedDenialStatus())
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

    @Test
    void requestPhotosReturnedWithSignedUrlsAndNoStorageKeyExposed() throws Exception {
        String ownerSubject = "auth0|owner-photos-signed";
        UUID ownerUserId = provisionOwner(ownerSubject);

        UUID m1 = uploadAndConfirmRepairRequestMedia(ownerSubject, ownerUserId);
        UUID m2 = uploadAndConfirmRepairRequestMedia(ownerSubject, ownerUserId);

        UUID requestId = createRequestWithMedia(ownerSubject, "PLUMBING", "Tubo roto con fotos",
                "Se adjuntan evidencias", List.of(m1, m2));

        var result = mvc.perform(get("/requests/" + requestId).with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.photos", hasSize(2)))
                .andExpect(jsonPath("$.photos[0].mediaId").value(m1.toString()))
                .andExpect(jsonPath("$.photos[0].readUrl", startsWith("http://localhost:9000/read/")))
                .andExpect(jsonPath("$.photos[0].readUrlExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.photos[1].mediaId").value(m2.toString()))
                // strictly no storageKey or objectKey exposed
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.bucket").doesNotExist())
                .andExpect(jsonPath("$.photoKeys").doesNotExist())
                .andExpect(jsonPath("$.photos[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.photos[0].objectKey").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("storageKey", "objectKey", "photoKeys");
    }

    @Test
    void requestCreationValidatesMediaOwnershipPurposeAndReady() throws Exception {
        String ownerSubject = "auth0|owner-media-validations";
        UUID ownerUserId = provisionOwner(ownerSubject);

        String otherOwnerSubject = "auth0|other-owner-media";
        UUID otherOwnerUserId = provisionOwner(otherOwnerSubject);
        java.util.UUID reqPropIdA = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", reqPropIdA, ownerUserId);
        UUID alienMedia = uploadAndConfirmRepairRequestMedia(otherOwnerSubject, otherOwnerUserId);

        // Alien media -> 404
        String alienReq = """
                {"propertyId":"%s","title":"Tubo","description":"Desc","mediaIds":["%s"]}
                """.formatted(reqPropIdA, alienMedia);
        mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(alienReq))
                .andExpect(status().isNotFound());

        // Duplicate media in request -> 400
        UUID myMedia = uploadAndConfirmRepairRequestMedia(ownerSubject, ownerUserId);
        String dupReq = """
                {"propertyId":"%s","title":"Tubo","description":"Desc","mediaIds":["%s","%s"]}
                """.formatted(reqPropIdA, myMedia, myMedia);
        mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(dupReq))
                .andExpect(status().isBadRequest());

        // More than 6 mediaIds -> 400
        var tooManyIds = java.util.stream.Stream.generate(UUID::randomUUID).limit(7).toList();
        String tooManyReq = """
                {"propertyId":"%s","title":"Tubo","description":"Desc","mediaIds":%s}
                """.formatted(reqPropIdA, mapper.writeValueAsString(tooManyIds));
        mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(tooManyReq))
                .andExpect(status().isBadRequest());

        // Media with purpose FIXER_PORTFOLIO cannot be attached to repair request -> 400
        UUID portfolioMedia = UUID.randomUUID();
        String portKey = "portfolio/" + ownerUserId + "/" + portfolioMedia + ".jpg";
        TestStorageConfiguration.instance().put(portKey, TestStorageConfiguration.JPEG_MAGIC, "image/jpeg");
        jdbc.update("INSERT INTO media_assets (id, owner_user_id, purpose, object_key, content_type, size_bytes, status, upload_expires_at, confirmed_at, created_at) "
                + "VALUES (?, ?, 'FIXER_PORTFOLIO', ?, 'image/jpeg', 100, 'READY', CURRENT_TIMESTAMP + INTERVAL '1' DAY, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                portfolioMedia, ownerUserId, portKey);
        java.util.UUID reqPropIdP = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", reqPropIdP, ownerUserId);
        String portReq = """
                {"propertyId":"%s","title":"Tubo","description":"Desc","mediaIds":["%s"]}
                """.formatted(reqPropIdP, portfolioMedia);
        mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(portReq))
                .andExpect(status().isBadRequest());

        // Successfully attach myMedia
        UUID req1 = createRequestWithMedia(ownerSubject, "PLUMBING", "Tubo", "Desc", List.of(myMedia));
        assertThat(req1).isNotNull();

        // Already ATTACHED media cannot be reused -> 409
        java.util.UUID reqPropIdR = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", reqPropIdR, ownerUserId);
        String reuseReq = """
                {"propertyId":"%s","title":"Otro tubo","description":"Desc","mediaIds":["%s"]}
                """.formatted(reqPropIdR, myMedia);
        mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(reuseReq))
                .andExpect(status().isConflict());
    }

    @Test
    void amountValidationAcceptsOneAndMaxAmountAndRejectsZeroNegativeAndOverflow() throws Exception {
        String ownerSubject = "auth0|owner-amount-limits";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PAINTING", "Pintar reja", "Pintura antioxidante");

        String fixerSubject = "auth0|fixer-amount-limits";
        provisionVerifiedFixer(fixerSubject, "PAINTING");

        // 1 is valid
        UUID q1 = submitQuotation(fixerSubject, requestId, 1L, 1, "Oferta mínima");
        assertThat(q1).isNotNull();

        // Second request for testing MAX_AMOUNT
        UUID request2Id = createRequest(ownerSubject, "PAINTING", "Pintar muro", "Pintura látex");
        UUID qMax = submitQuotation(fixerSubject, request2Id, Quotation.MAX_AMOUNT, 1, "Oferta máxima");
        assertThat(qMax).isNotNull();

        // 0 rejected -> 400
        UUID request3Id = createRequest(ownerSubject, "PAINTING", "Pintar techo", "Pintura blanca");
        String body0 = """
                {"requestId":"%s","amount":0,"estimatedDays":1}
                """.formatted(request3Id);
        mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body0))
                .andExpect(status().isBadRequest());

        // Negative rejected -> 400
        String bodyNeg = """
                {"requestId":"%s","amount":-100,"estimatedDays":1}
                """.formatted(request3Id);
        mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(bodyNeg))
                .andExpect(status().isBadRequest());

        // MAX_AMOUNT + 1 (9007199254740992) rejected -> 400
        String bodyOverflow = """
                {"requestId":"%s","amount":9007199254740992,"estimatedDays":1}
                """.formatted(request3Id);
        mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(bodyOverflow))
                .andExpect(status().isBadRequest());
    }

    @Test
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
                        .contentType(MediaType.APPLICATION_JSON).content(quotationBBody))
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
        assertThat(submitStatus.get()).isIn(201, 409);

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

    @Test
    void fixerWithoutSpecialtiesReceivesNoOpenRequests() throws Exception {
        String ownerSubject = "auth0|owner-spec-filter";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "ELECTRICAL", "Toma quemada", "Revisar cableado");

        String fixerSubject = "auth0|fixer-no-specs";
        provisionVerifiedFixer(fixerSubject); // VERIFIED, but 0 specialties

        // Receives 0 requests
        mvc.perform(get("/requests/open").with(identity(fixerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Configure compatible specialty via POST /fixers/me/specialties
        mvc.perform(post("/fixers/me/specialties").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"specialties\":[\"ELECTRICAL\"]}"))
                .andExpect(status().isOk());

        // Now receives the request
        mvc.perform(get("/requests/open").with(identity(fixerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()));

        // Incompatible request
        UUID plumbingReq = createRequest(ownerSubject, "PLUMBING", "Llave", "Fuga");
        mvc.perform(get("/requests/open").with(identity(fixerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()));
    }

    /**
     * FR-UC-18: GET /quotations/for-request/{requestId} — el tablero comparativo del propietario,
     * mas barata primero. Era la ultima ruta de quotations sin prueba de contrato HTTP.
     */
    @Test
    void ownerComparesReceivedQuotationsCheapestFirst() throws Exception {
        String ownerSubject = "auth0|owner-compare";
        provisionOwner(ownerSubject);
        UUID requestId = createRequest(ownerSubject, "PLUMBING", "Cambio de tuberia",
                "Reemplazo de la tuberia de la cocina");

        provisionVerifiedFixer("auth0|fixer-expensive", "PLUMBING");
        provisionVerifiedFixer("auth0|fixer-cheap", "PLUMBING");
        UUID expensive = submitQuotation("auth0|fixer-expensive", requestId, 900000, 5,
                "Incluye materiales");
        UUID cheap = submitQuotation("auth0|fixer-cheap", requestId, 500000, 7,
                "Solo mano de obra");

        mvc.perform(get("/quotations/for-request/" + requestId).with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(cheap.toString()))
                .andExpect(jsonPath("$[0].amount").value(500000))
                .andExpect(jsonPath("$[1].id").value(expensive.toString()));

        // Otro propietario no abre las ofertas de una solicitud ajena.
        String strangerSubject = "auth0|owner-compare-stranger";
        provisionOwner(strangerSubject);
        mvc.perform(get("/quotations/for-request/" + requestId).with(identity(strangerSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** FR-UC-18: GET /quotations/me — cada tecnico ve sus ofertas enviadas y solo las suyas. */
    @Test
    void fixerListsOnlyOwnQuotations() throws Exception {
        String ownerSubject = "auth0|owner-inbox-list";
        provisionOwner(ownerSubject);
        UUID firstRequest = createRequest(ownerSubject, "ELECTRICAL", "Tablero electrico",
                "Revision del tablero principal");
        UUID secondRequest = createRequest(ownerSubject, "ELECTRICAL", "Tomas danadas",
                "Cambio de tomacorrientes del segundo piso");

        provisionVerifiedFixer("auth0|fixer-mine", "ELECTRICAL");
        provisionVerifiedFixer("auth0|fixer-other", "ELECTRICAL");
        UUID first = submitQuotation("auth0|fixer-mine", firstRequest, 300000, 2,
                "Diagnostico incluido");
        UUID second = submitQuotation("auth0|fixer-mine", secondRequest, 450000, 3,
                "Materiales aparte");
        submitQuotation("auth0|fixer-other", firstRequest, 800000, 1, "Servicio express");

        mvc.perform(get("/quotations/me").with(identity("auth0|fixer-mine")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.containsInAnyOrder(
                        first.toString(), second.toString())));

        mvc.perform(get("/quotations/me").with(identity("auth0|fixer-other")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Un propietario no es tecnico: no tiene bandeja de ofertas enviadas.
        mvc.perform(get("/quotations/me").with(identity(ownerSubject)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
