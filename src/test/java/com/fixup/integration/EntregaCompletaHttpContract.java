package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los casos que completan la primera entrega: publicación (FR-UC-02), sello
 * (FR-UC-07), visitas (FR-UC-03 y 27), pago (FR-UC-05 y 22), reporte del arrendatario (FR-UC-08),
 * calificación (FR-UC-09), avisos (FR-UC-10), gestión inmobiliaria (FR-UC-11), reportes de gastos
 * (FR-UC-13), trabajos con estados (FR-UC-19) y remodelación pre-arriendo (FR-UC-26). La misma
 * suite corre contra H2 y contra PostgreSQL con RLS activo.
 */
abstract class EntregaCompletaHttpContract {
    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    /** H2 answers 403 for a request the caller has no relation with; under RLS the row is just invisible. */
    protected abstract org.springframework.test.web.servlet.ResultMatcher expectedHiddenRequestStatus();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void cleanDatabase() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
        TestStorageConfiguration.instance().clear();
    }

    // ---------- helpers ----------

    RequestPostProcessor as(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Usuario " + subject.substring(subject.indexOf('|') + 1)));
    }

    UUID provision(String subject, String... roles) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(as(subject))).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        for (String role : roles) {
            if ("FIXER".equals(role)) {
                mvc.perform(post("/auth/select-role").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"FIXER\"}")).andExpect(status().isOk());
            } else {
                jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
            }
        }
        return id;
    }

    UUID verifiedFixer(String subject, String city, String... specialties) throws Exception {
        UUID id = provision(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED', city = ? WHERE user_id = ?", city, id);
        for (String specialty : specialties) {
            jdbc.update("INSERT INTO fixer_specialties(fixer_user_id, specialty) VALUES (?, ?)", id, specialty);
        }
        return id;
    }

    JsonNode json(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    JsonNode createProperty(String ownerSubject, String body) throws Exception {
        return json(mvc.perform(post("/properties").with(as(ownerSubject)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()));
    }

    String listingBody(String name, String city, boolean publish) {
        return """
                {"name":"%s","address":"Carrera 7 # 45-12","city":"%s","areaM2":72.5,
                 "latitude":4.6352,"longitude":-74.0648,"listingType":"RENT","propertyType":"APARTMENT",
                 "price":2300000,"adminFee":280000,"bedrooms":3,"bathrooms":2,"parkingSpots":1,"stratum":4,
                 "neighborhood":"Chapinero Alto","description":"Apartamento iluminado con balcón","publish":%s}
                """.formatted(name, city, publish);
    }

    UUID property(String ownerSubject) throws Exception {
        return UUID.fromString(createProperty(ownerSubject, listingBody("Apartamento Chapinero", "Bogotá", true))
                .get("id").asText());
    }

    UUID openRequest(String subject, UUID propertyId, String title, String description) throws Exception {
        var body = """
                {"propertyId":"%s","title":"%s","description":"%s","mediaIds":[]}
                """.formatted(propertyId, title, description);
        return UUID.fromString(json(mvc.perform(post("/requests").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()))
                .get("requestId").asText());
    }

    UUID plumbingRequest(String subject, UUID propertyId) throws Exception {
        return openRequest(subject, propertyId, "Fuga bajo el lavaplatos",
                "La tubería de agua gotea y moja el mueble de la cocina");
    }

    UUID quote(String fixerSubject, UUID requestId, long amount) throws Exception {
        var body = """
                {"requestId":"%s","amount":%d,"estimatedDays":2,"message":"Cambio del sifón y prueba de presión"}
                """.formatted(requestId, amount);
        return UUID.fromString(json(mvc.perform(post("/quotations").with(as(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()))
                .get("id").asText());
    }

    void accept(String subject, UUID quotationId) throws Exception {
        mvc.perform(post("/quotations/" + quotationId + "/accept").with(as(subject))).andExpect(status().isOk());
    }

    void pay(String payerSubject, UUID requestId) throws Exception {
        var charge = json(mvc.perform(get("/payments/charges/for-request/" + requestId).with(as(payerSubject)))
                .andExpect(status().isOk()));
        mvc.perform(post("/payments/charges/" + charge.get("id").asText() + "/pay").with(as(payerSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"%s\",\"amount\":%d,\"method\":\"SANDBOX_CARD\"}"
                                .formatted(charge.get("reference").asText(), charge.get("amount").asLong())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    UUID jobFor(String subject, UUID requestId) throws Exception {
        return UUID.fromString(json(mvc.perform(get("/jobs/for-request/" + requestId).with(as(subject)))
                .andExpect(status().isOk())).at("/job/id").asText());
    }

    void complete(String fixerSubject, UUID jobId) throws Exception {
        mvc.perform(post("/jobs/" + jobId + "/complete").with(as(fixerSubject))).andExpect(status().isOk());
    }

    /** Solicitud de plomería cotizada, aceptada, pagada y terminada. Devuelve el id del trabajo. */
    UUID completedPlumbingJob(String ownerSubject, String fixerSubject, UUID propertyId, long amount) throws Exception {
        UUID requestId = plumbingRequest(ownerSubject, propertyId);
        accept(ownerSubject, quote(fixerSubject, requestId, amount));
        pay(ownerSubject, requestId);
        UUID jobId = jobFor(fixerSubject, requestId);
        complete(fixerSubject, jobId);
        return jobId;
    }

    String tomorrowAt(int hour) {
        var date = ZonedDateTime.now(BOGOTA).plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0);
        return date.toInstant().toString();
    }

    long notificationsOf(UUID userId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?",
                Long.class, userId, type);
    }

    // ---------- FR-UC-02, FR-UC-06: aviso, publicación y catálogo ----------

    @Test
    void ownerPublishesAndWithdrawsAListingThatTheCatalogFilters() throws Exception {
        String owner = "auth0|listing-owner";
        String tenant = "auth0|listing-tenant";
        String fixer = "auth0|listing-fixer";
        UUID ownerId = provision(owner, "OWNER");
        provision(tenant, "TENANT");
        verifiedFixer(fixer, "Bogotá", "PLUMBING");

        var draft = createProperty(owner, listingBody("Apartaestudio Teusaquillo", "Bogotá", false));
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("price").asLong()).isEqualTo(2_300_000L);
        assertThat(draft.get("stratum").asInt()).isEqualTo(4);
        UUID propertyId = UUID.fromString(draft.get("id").asText());

        mvc.perform(get("/properties/catalog").with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));

        mvc.perform(post("/properties/" + propertyId + "/publish").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(notificationsOf(ownerId, "PROPERTY_PUBLISHED")).isEqualTo(1);

        mvc.perform(get("/properties/catalog").with(as(tenant)).queryParam("city", "bogotá")
                        .queryParam("listingType", "RENT").queryParam("maxPrice", "2500000")
                        .queryParam("minBedrooms", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].neighborhood").value("Chapinero Alto"))
                .andExpect(jsonPath("$.items[0].seal").value(false));
        mvc.perform(get("/properties/catalog").with(as(tenant)).queryParam("maxPrice", "1000000"))
                .andExpect(jsonPath("$.totalItems").value(0));

        mvc.perform(get("/properties/" + propertyId + "/listing").with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Apartamento iluminado con balcón"))
                .andExpect(jsonPath("$.ownerDisplayName").isNotEmpty());
        mvc.perform(get("/properties/catalog").with(as(fixer))).andExpect(status().isForbidden());

        mvc.perform(put("/properties/" + propertyId).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Apartaestudio Teusaquillo","address":"Calle 39 # 17-20","city":"Bogotá",
                                 "areaM2":48,"listingType":"RENT","propertyType":"STUDIO","price":1650000,
                                 "bedrooms":1,"bathrooms":1,"stratum":3,"neighborhood":"Teusaquillo"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(1650000))
                .andExpect(jsonPath("$.propertyType").value("STUDIO"));
        mvc.perform(put("/properties/" + propertyId).with(as(tenant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"address\":\"Y\",\"city\":\"Bogotá\",\"areaM2\":10}"))
                .andExpect(status().is4xxClientError());

        mvc.perform(post("/properties/" + propertyId + "/unpublish").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(get("/properties/catalog").with(as(tenant))).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(post("/properties/" + propertyId + "/unpublish").with(as(owner)))
                .andExpect(status().isConflict());
    }

    // ---------- FR-UC-08: el arrendatario reporta la falla ----------

    @Test
    void tenantWithAnActiveLeaseReportsAFailureThatTheOwnerDecides() throws Exception {
        String owner = "auth0|lease-owner";
        String tenant = "auth0|lease-tenant";
        String outsider = "auth0|lease-outsider";
        UUID ownerId = provision(owner, "OWNER");
        UUID tenantId = provision(tenant, "TENANT");
        provision(outsider, "TENANT");
        UUID propertyId = property(owner);

        mvc.perform(post("/contracts").with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"propertyId":"%s","tenantUserId":"%s","startDate":"%s","endDate":"%s",
                         "monthlyRentAmount":2300000,"depositAmount":2300000,"paymentDayOfMonth":5,"notes":"Contrato a un año"}
                        """.formatted(propertyId, tenantId, LocalDate.now().minusMonths(2), LocalDate.now().plusMonths(10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyName").value("Apartamento Chapinero"))
                .andExpect(jsonPath("$.tenantName").isNotEmpty());

        mvc.perform(get("/requests/reportable-properties").with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].propertyId").value(propertyId.toString()));

        UUID requestId = plumbingRequest(tenant, propertyId);
        mvc.perform(get("/requests/" + requestId).with(as(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(ownerId.toString()))
                .andExpect(jsonPath("$.requestedByUserId").value(tenantId.toString()))
                .andExpect(jsonPath("$.propertyAddress").value("Carrera 7 # 45-12"));
        mvc.perform(get("/requests/me").with(as(owner)))
                .andExpect(jsonPath("$[*].requestId", hasItem(requestId.toString())));
        assertThat(notificationsOf(ownerId, "REQUEST_REPORTED")).isEqualTo(1);

        // Sin contrato vigente no hay relación con el inmueble: 404, igual que un inmueble ajeno.
        mvc.perform(post("/requests").with(as(outsider)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyId\":\"%s\",\"title\":\"Luz\",\"description\":\"No prende\"}".formatted(propertyId)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/requests/" + requestId).with(as(outsider))).andExpect(expectedHiddenRequestStatus());

        // El arrendatario retira su reporte mientras nadie lo ha tomado.
        mvc.perform(post("/requests/" + requestId + "/cancel").with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------- FR-UC-11: gestión inmobiliaria ----------

    @Test
    void managerSeesDelegatedRequestsRanksFixersInvitesAndDecides() throws Exception {
        String owner = "auth0|managed-owner";
        String manager = "auth0|managed-manager";
        String nearFixer = "auth0|managed-fixer-near";
        String farFixer = "auth0|managed-fixer-far";
        provision(owner, "OWNER");
        UUID managerId = provision(manager, "REAL_ESTATE_MANAGER");
        UUID nearId = verifiedFixer(nearFixer, "Bogotá", "PLUMBING");
        UUID farId = verifiedFixer(farFixer, "Medellín", "PLUMBING");
        UUID propertyId = property(owner);

        mvc.perform(get("/properties/managers").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(managerId.toString())));
        mvc.perform(put("/properties/" + propertyId + "/manager").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"managerUserId\":\"%s\"}".formatted(managerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.managerUserId").value(managerId.toString()));
        mvc.perform(get("/properties/me").with(as(manager)))
                .andExpect(jsonPath("$[*].id", hasItem(propertyId.toString())));

        UUID requestId = plumbingRequest(owner, propertyId);
        mvc.perform(get("/management/requests").with(as(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].requestId").value(requestId.toString()));

        var candidates = json(mvc.perform(get("/management/requests/" + requestId + "/candidates").with(as(manager)))
                .andExpect(status().isOk()));
        assertThat(candidates.get(0).get("fixerUserId").asText()).isEqualTo(nearId.toString());
        assertThat(candidates.get(0).get("sameCity").asBoolean()).isTrue();

        // El técnico de otra ciudad no ve la solicitud hasta que lo invitan.
        mvc.perform(get("/requests/open").with(as(farFixer)))
                .andExpect(jsonPath("$[*].requestId", not(hasItem(requestId.toString()))));
        mvc.perform(post("/management/requests/" + requestId + "/invitations").with(as(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fixerUserIds\":[\"%s\"],\"note\":\"Cliente prioritario\"}".formatted(farId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invitedFixerUserIds[0]").value(farId.toString()));
        mvc.perform(get("/requests/open").with(as(farFixer)))
                .andExpect(jsonPath("$[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$[0].invited").value(true));
        assertThat(notificationsOf(farId, "REQUEST_INVITATION")).isEqualTo(1);

        UUID quotationId = quote(farFixer, requestId, 380_000);
        mvc.perform(get("/quotations/for-request/" + requestId).with(as(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].fixerName").isNotEmpty());
        accept(manager, quotationId);
        pay(manager, requestId);

        String stranger = "auth0|managed-stranger";
        provision(stranger, "REAL_ESTATE_MANAGER");
        mvc.perform(get("/management/requests/" + requestId + "/candidates").with(as(stranger)))
                .andExpect(status().isForbidden());
    }

    // ---------- FR-UC-19: estados del trabajo ----------

    @Test
    void jobIsScheduledStartedDocumentedAndClosedWhichClosesTheRequest() throws Exception {
        String owner = "auth0|lifecycle-owner";
        String fixer = "auth0|lifecycle-fixer";
        UUID ownerId = provision(owner, "OWNER");
        verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID requestId = plumbingRequest(owner, property(owner));
        accept(owner, quote(fixer, requestId, 250_000));
        UUID jobId = jobFor(fixer, requestId);

        mvc.perform(post("/jobs/" + jobId + "/schedule").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledFor\":\"%s\",\"note\":\"Llevo el repuesto\"}".formatted(tomorrowAt(9))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
        assertThat(notificationsOf(ownerId, "JOB_STATUS_CHANGED")).isEqualTo(1);
        mvc.perform(post("/jobs/" + jobId + "/start").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/jobs/" + jobId + "/start").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Cerré el registro\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        // Una visita de ejecución que seguía propuesta se cancela sola cuando el trabajo termina.
        UUID pendingVisit = UUID.fromString(json(mvc.perform(post("/visits").with(as(fixer))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"%s","kind":"EXECUTION","scheduledAt":"%s"}""".formatted(requestId, tomorrowAt(15))))
                .andExpect(status().isCreated())).get("id").asText());

        var upload = json(mvc.perform(post("/media/uploads").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"JOB_EVIDENCE\",\"contentType\":\"image/jpeg\",\"sizeBytes\":6}"))
                .andExpect(status().isCreated()));
        UUID mediaId = UUID.fromString(upload.get("mediaId").asText());
        String objectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class, mediaId);
        TestStorageConfiguration.instance().put(objectKey, TestStorageConfiguration.JPEG_MAGIC, "image/jpeg");
        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(as(fixer))).andExpect(status().isOk());
        mvc.perform(post("/jobs/" + jobId + "/evidence").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaIds\":[\"%s\"],\"stage\":\"AFTER\",\"caption\":\"Sifón nuevo\"}".formatted(mediaId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].stage").value("AFTER"));

        mvc.perform(post("/requests/" + requestId + "/messages").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Gracias, ¿quedó sin fugas?\"}")).andExpect(status().isCreated());
        complete(fixer, jobId);

        var detail = json(mvc.perform(get("/jobs/" + jobId).with(as(owner))).andExpect(status().isOk()));
        assertThat(detail.at("/job/status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.at("/job/specialty").asText()).isNotBlank();
        mvc.perform(get("/visits/" + pendingVisit).with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("El trabajo de esta solicitud ya terminó."));
        assertThat(detail.get("log")).hasSize(4);
        assertThat(detail.get("evidence")).hasSize(1);
        mvc.perform(get("/requests/" + requestId).with(as(owner)))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.completedAt").isNotEmpty());
        // FR-UC-24: la conversación de una solicitud cerrada queda solo para lectura.
        mvc.perform(post("/requests/" + requestId + "/messages").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Otra cosa\"}")).andExpect(status().isConflict());
        mvc.perform(get("/requests/" + requestId + "/messages").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].body").value("Gracias, ¿quedó sin fugas?"));
        assertThat(notificationsOf(ownerId, "JOB_COMPLETED")).isEqualTo(1);
    }

    // ---------- FR-UC-09 y FR-UC-07: calificación, reputación y sello ----------

    @Test
    void clientRatesTheCompletedJobWhichFeedsTheFixerReputationAndThePropertySeal() throws Exception {
        String owner = "auth0|rating-owner";
        String fixer = "auth0|rating-fixer";
        String tenant = "auth0|rating-tenant";
        provision(owner, "OWNER");
        provision(tenant, "TENANT");
        UUID fixerId = verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID propertyId = property(owner);
        UUID jobId = completedPlumbingJob(owner, fixer, propertyId, 300_000);

        mvc.perform(post("/ratings").with(as(tenant)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"%s\",\"score\":5}".formatted(jobId))).andExpect(status().isForbidden());
        mvc.perform(post("/ratings").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobId":"%s","score":5,"punctuality":5,"quality":4,"cleanliness":5,
                                 "comment":"Llegó puntual y dejó todo limpio"}
                                """.formatted(jobId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.score").value(5));
        mvc.perform(post("/ratings").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":\"%s\",\"score\":1}".formatted(jobId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RATED"));
        assertThat(notificationsOf(fixerId, "RATING_RECEIVED")).isEqualTo(1);

        mvc.perform(get("/fixers/" + fixerId + "/profile").with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ratingAverage").value(5.0))
                .andExpect(jsonPath("$.ratingCount").value(1));
        mvc.perform(get("/ratings/fixers/" + fixerId).with(as(tenant)))
                .andExpect(jsonPath("$.reviews[0].comment").value("Llegó puntual y dejó todo limpio"))
                .andExpect(jsonPath("$.distribution[4]").value(1));
        mvc.perform(get("/fixers/directory").with(as(tenant)).queryParam("specialty", "PLUMBING"))
                .andExpect(jsonPath("$[0].fixerUserId").value(fixerId.toString()));

        mvc.perform(get("/properties/" + propertyId + "/listing").with(as(tenant)))
                .andExpect(jsonPath("$.seal.granted").value(true))
                .andExpect(jsonPath("$.workHistory[0].rating").value(5));
        mvc.perform(get("/properties/catalog").with(as(tenant)).queryParam("sealOnly", "true"))
                .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].seal").value(true));
    }

    // ---------- FR-UC-03 y FR-UC-27: visitas técnicas ----------

    @Test
    void ownerAndQuotedFixerAgreeAKitchenAssessmentVisitWithItsChecklist() throws Exception {
        String owner = "auth0|visit-owner";
        String fixer = "auth0|visit-fixer";
        String other = "auth0|visit-other-fixer";
        UUID ownerId = provision(owner, "OWNER");
        UUID fixerId = verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID otherId = verifiedFixer(other, "Bogotá", "PLUMBING");
        UUID requestId = plumbingRequest(owner, property(owner));
        quote(fixer, requestId, 900_000);

        // Solo con un técnico que cotizó, y en horario de visita.
        mvc.perform(post("/visits").with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"%s","fixerUserId":"%s","kind":"KITCHEN_ASSESSMENT","scheduledAt":"%s"}
                        """.formatted(requestId, otherId, tomorrowAt(10))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FIXER_NOT_QUOTED"));
        mvc.perform(post("/visits").with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"%s","fixerUserId":"%s","kind":"KITCHEN_ASSESSMENT","scheduledAt":"%s"}
                        """.formatted(requestId, fixerId, tomorrowAt(21))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUTSIDE_HOURS"));

        var visit = json(mvc.perform(post("/visits").with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"%s","fixerUserId":"%s","kind":"KITCHEN_ASSESSMENT","scheduledAt":"%s",
                         "durationMinutes":90,"notes":"Revisar gas y mesón"}
                        """.formatted(requestId, fixerId, tomorrowAt(10))))
                .andExpect(status().isCreated()));
        UUID visitId = UUID.fromString(visit.get("id").asText());
        assertThat(visit.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(visit.get("checklist")).hasSize(7);
        assertThat(notificationsOf(fixerId, "VISIT_PROPOSED")).isEqualTo(1);

        mvc.perform(post("/visits/" + visitId + "/confirm").with(as(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_CONFIRMATION"));
        mvc.perform(post("/visits/" + visitId + "/reschedule").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledAt\":\"%s\",\"durationMinutes\":90}".formatted(tomorrowAt(14))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"));
        mvc.perform(post("/visits/" + visitId + "/confirm").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(notificationsOf(fixerId, "VISIT_CONFIRMED")).isEqualTo(1);

        // Otro cliente puede proponer esa franja, pero el técnico no puede confirmar dos visitas encima.
        String secondOwner = "auth0|visit-owner-two";
        provision(secondOwner, "OWNER");
        UUID secondRequest = plumbingRequest(secondOwner, property(secondOwner));
        quote(fixer, secondRequest, 100_000);
        var clash = json(mvc.perform(post("/visits").with(as(secondOwner)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"%s","fixerUserId":"%s","kind":"DIAGNOSTIC","scheduledAt":"%s"}
                        """.formatted(secondRequest, fixerId, tomorrowAt(14))))
                .andExpect(status().isCreated()));
        mvc.perform(post("/visits/" + clash.get("id").asText() + "/confirm").with(as(fixer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FIXER_BUSY"));

        jdbc.update("UPDATE technical_visits SET scheduled_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)), visitId);
        mvc.perform(post("/visits/" + visitId + "/complete").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"Cocina en buen estado salvo el mesón\",\"checklist\":[{\"position\":0,\"result\":\"OK\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKLIST_INCOMPLETE"));
        var answers = new StringBuilder("[");
        for (int position = 0; position < 7; position++) {
            answers.append(position == 0 ? "" : ",").append("{\"position\":").append(position)
                    .append(",\"result\":\"").append(position == 4 ? "NEEDS_WORK" : "OK").append("\"}");
        }
        answers.append(']');
        mvc.perform(post("/visits/" + visitId + "/complete").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"Cambiar el mesón y resellar el salpicadero\",\"checklist\":" + answers + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.checklist[4].result").value("NEEDS_WORK"));
        mvc.perform(get("/visits/me").with(as(owner)))
                .andExpect(jsonPath("$[0].fixerName").isNotEmpty());
        mvc.perform(get("/visits/" + visitId).with(as(other))).andExpect(status().isNotFound());
        assertThat(ownerId).isNotNull();
    }

    // ---------- FR-UC-13: reporte de gastos ----------

    @Test
    void expenseReportAddsWhatTheOwnerActuallyPaidByPropertyAndSpecialty() throws Exception {
        String owner = "auth0|report-owner";
        String fixer = "auth0|report-fixer";
        provision(owner, "OWNER");
        verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID first = property(owner);
        UUID second = UUID.fromString(createProperty(owner, listingBody("Casa Envigado", "Envigado", true))
                .get("id").asText());
        completedPlumbingJob(owner, fixer, first, 200_000);
        completedPlumbingJob(owner, fixer, second, 450_000);
        // Una cotización aceptada sin pagar no es gasto.
        UUID unpaid = plumbingRequest(owner, first);
        accept(owner, quote(fixer, unpaid, 999_000));

        var today = LocalDate.now(BOGOTA);
        mvc.perform(get("/analytics/expenses").with(as(owner))
                        .queryParam("from", today.minusDays(30).toString()).queryParam("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(650_000))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.byProperty[0].propertyName").value("Casa Envigado"))
                .andExpect(jsonPath("$.bySpecialty[0].specialty").value("PLUMBING"));
        mvc.perform(get("/analytics/expenses").with(as(owner)).queryParam("propertyId", first.toString())
                        .queryParam("from", today.minusDays(30).toString()).queryParam("to", today.toString()))
                .andExpect(jsonPath("$.total").value(200_000));
        mvc.perform(get("/analytics/expenses/csv").with(as(owner))
                        .queryParam("from", today.minusDays(30).toString()).queryParam("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOTAL,650000")));
        mvc.perform(get("/analytics/expenses").with(as(fixer))
                        .queryParam("from", today.minusDays(30).toString()).queryParam("to", today.toString()))
                .andExpect(status().isForbidden());
    }

    // ---------- FR-UC-26: remodelación pre-arriendo ----------

    @Test
    void preRentalRemodelingPublishesTheRentalListingOnlyWhenEveryRequirementIsDone() throws Exception {
        String owner = "auth0|prerental-owner";
        String plumber = "auth0|prerental-plumber";
        String electrician = "auth0|prerental-electrician";
        provision(owner, "OWNER");
        verifiedFixer(plumber, "Bogotá", "PLUMBING");
        verifiedFixer(electrician, "Bogotá", "ELECTRICAL");
        UUID propertyId = UUID.fromString(createProperty(owner, listingBody("Casa para arrendar", "Bogotá", false))
                .get("id").asText());

        var project = json(mvc.perform(post("/remodelings").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"propertyId":"%s","title":"Dejar lista para arrendar","goal":"PRE_RENTAL","targetRent":2800000,
                         "requirements":[
                           {"title":"Arreglar fuga","description":"Fuga de agua en la tubería del baño","mediaIds":[]},
                           {"title":"Cambiar tablero","description":"Cableado eléctrico y breaker nuevos","mediaIds":[]}]}
                        """.formatted(propertyId))).andExpect(status().isCreated()));
        UUID projectId = UUID.fromString(project.get("id").asText());
        assertThat(project.get("readyToPublish").asBoolean()).isFalse();
        mvc.perform(post("/remodelings/" + projectId + "/publish-listing").with(as(owner)))
                .andExpect(status().isConflict());

        for (var requirement : project.get("requirements")) {
            UUID requestId = UUID.fromString(requirement.get("requestId").asText());
            String fixer = "PLUMBING".equals(requirement.get("specialty").asText()) ? plumber : electrician;
            accept(owner, quote(fixer, requestId, 500_000));
            pay(owner, requestId);
            complete(fixer, jobFor(fixer, requestId));
        }

        mvc.perform(get("/remodelings/" + projectId).with(as(owner)))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.readyToPublish").value(true));
        mvc.perform(post("/remodelings/" + projectId + "/publish-listing").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.listingPublishedAt").isNotEmpty());
        mvc.perform(get("/properties/" + propertyId).with(as(owner)))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.listingType").value("RENT"))
                .andExpect(jsonPath("$.price").value(2800000));
        mvc.perform(post("/remodelings/" + projectId + "/publish-listing").with(as(owner)))
                .andExpect(status().isConflict());
    }

    // ---------- FR-UC-10 y FR-UC-16: avisos y cola de revisión ----------

    @Test
    void quotationEventsReachTheirRecipientsAndAdminsSeeTheReviewQueue() throws Exception {
        String owner = "auth0|notify-owner";
        String fixer = "auth0|notify-fixer";
        String loser = "auth0|notify-loser";
        String admin = "auth0|notify-admin";
        String applicant = "auth0|notify-applicant";
        UUID ownerId = provision(owner, "OWNER");
        UUID fixerId = verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID loserId = verifiedFixer(loser, "Bogotá", "PLUMBING");
        provision(admin, "PLATFORM_ADMIN");
        UUID applicantId = provision(applicant, "FIXER");
        UUID requestId = plumbingRequest(owner, property(owner));

        UUID winning = quote(fixer, requestId, 210_000);
        quote(loser, requestId, 260_000);
        assertThat(notificationsOf(ownerId, "QUOTATION_RECEIVED")).isEqualTo(2);
        accept(owner, winning);
        assertThat(notificationsOf(fixerId, "QUOTATION_ACCEPTED")).isEqualTo(1);
        assertThat(notificationsOf(loserId, "QUOTATION_REJECTED")).isEqualTo(1);
        mvc.perform(get("/notifications/me").with(as(fixer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("QUOTATION_ACCEPTED"));

        jdbc.update("UPDATE fixer_profiles SET submitted_at = CURRENT_TIMESTAMP WHERE user_id = ?", applicantId);
        mvc.perform(get("/fixers/verification/pending").with(as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].fixerUserId").value(applicantId.toString()));
        mvc.perform(get("/fixers/verification/pending").with(as(owner))).andExpect(status().isForbidden());
        mvc.perform(put("/fixers/me/profile").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Bogotá\",\"bio\":\"Plomero con taller en Kennedy\",\"yearsExperience\":12}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.yearsExperience").value(12));
    }
}
