package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Recovered non-financial delivery flows. The helper always opens a TRANSFER request and approves
 * its canonical RepairPayment before a job is expected; this class deliberately has no CASH or
 * OwnerCharge fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@DirtiesContext
@Testcontainers
class PostgresRecoveredDeliveryIT {
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        cleaner.clean();
        TestStorageConfiguration.instance().clear();
    }

    @Test
    void listingPublicationAndWithdrawalRemainVisibleOnlyWhilePublished() throws Exception {
        String owner = "auth0|recovered-listing-owner";
        String tenant = "auth0|recovered-listing-tenant";
        provision(owner, "OWNER");
        provision(tenant, "TENANT");
        UUID property = createProperty(owner, false, "Apartaestudio recuperado");

        mvc.perform(get("/properties/catalog").with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(post("/properties/" + property + "/publish").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(get("/properties/catalog").with(as(tenant)).queryParam("city", "Bogotá")
                        .queryParam("listingType", "RENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(post("/properties/" + property + "/unpublish").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(get("/properties/catalog").with(as(tenant)))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void delegatedManagerRanksInvitesAcceptsAndPaysThroughCanonicalPayment() throws Exception {
        String owner = "auth0|recovered-manager-owner";
        String manager = "auth0|recovered-manager";
        String near = "auth0|recovered-near";
        String far = "auth0|recovered-far";
        UUID ownerId = provision(owner, "OWNER");
        UUID managerId = provision(manager, "REAL_ESTATE_MANAGER");
        UUID nearId = verifiedFixer(near, "Bogotá", "PLUMBING");
        UUID farId = verifiedFixer(far, "Medellín", "PLUMBING");
        UUID property = createProperty(owner, true, "Inmueble delegado recuperado");
        mvc.perform(put("/properties/" + property + "/manager").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"managerUserId\":\"" + managerId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.managerUserId").value(managerId.toString()));
        UUID request = request(owner, property, "Fuga de agua en cocina");
        JsonNode candidates = json(mvc.perform(get("/management/requests/" + request + "/candidates").with(as(manager)))
                .andExpect(status().isOk()));
        boolean nearCandidateFound = false;
        for (JsonNode candidate : candidates) {
            if (nearId.toString().equals(candidate.get("fixerUserId").asText())) {
                nearCandidateFound = candidate.get("sameCity").asBoolean();
            }
        }
        assertThat(nearCandidateFound).isTrue();
        mvc.perform(get("/requests/open").with(as(far)))
                .andExpect(jsonPath("$[*].requestId", not(hasItem(request.toString()))));
        mvc.perform(post("/management/requests/" + request + "/invitations").with(as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fixerUserIds\":[\"" + farId + "\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/requests/open").with(as(far)))
                .andExpect(jsonPath("$[0].requestId").value(request.toString()))
                .andExpect(jsonPath("$[0].invited").value(true));
        UUID quotation = quote(far, request, 380_000);
        UUID payment = accept(manager, quotation);
        approve(manager, payment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE quotation_id=?", Integer.class, quotation)).isEqualTo(1);
        assertThat(notificationCount(farId, "REQUEST_INVITATION")).isEqualTo(1);
    }

    @Test
    void transferJobIsScheduledStartedDocumentedClosedAndChatBecomesReadOnly() throws Exception {
        String owner = "auth0|recovered-life-owner";
        String fixer = "auth0|recovered-life-fixer";
        UUID ownerId = provision(owner, "OWNER");
        verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID request = request(owner, fixtureProperty(ownerId), "Fuga debajo del lavaplatos");
        UUID payment = accept(owner, quote(fixer, request, 250_000));
        approve(owner, payment);
        UUID job = job(request);
        mvc.perform(post("/jobs/" + job + "/schedule").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduledFor\":\"" + tomorrow(9) + "\",\"note\":\"Repuesto\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
        mvc.perform(post("/jobs/" + job + "/start").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Inicio\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        UUID media = readyEvidence(fixer);
        mvc.perform(post("/jobs/" + job + "/evidence").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaIds\":[\"" + media + "\"],\"stage\":\"AFTER\",\"caption\":\"Sifón nuevo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].stage").value("AFTER"));
        mvc.perform(post("/requests/" + request + "/messages").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Gracias\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/jobs/" + job + "/complete").with(as(fixer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/jobs/" + job).with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidence").isArray())
                .andExpect(jsonPath("$.log").isArray());
        mvc.perform(post("/requests/" + request + "/messages").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Otra pregunta\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_NOT_ASSIGNED"));
        mvc.perform(get("/requests/" + request + "/messages").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].body").value("Gracias"));
    }

    @Test
    void completedDigitalWorkFeedsRatingReputationSealAndPublicReview() throws Exception {
        String owner = "auth0|recovered-rating-owner";
        String fixer = "auth0|recovered-rating-fixer";
        String tenant = "auth0|recovered-rating-tenant";
        UUID ownerId = provision(owner, "OWNER");
        provision(tenant, "TENANT");
        UUID fixerId = verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID property = fixtureProperty(ownerId);
        UUID request = request(owner, property, "Fuga en baño");
        UUID payment = accept(owner, quote(fixer, request, 300_000));
        approve(owner, payment);
        UUID job = job(request);
        mvc.perform(post("/jobs/" + job + "/complete").with(as(fixer))).andExpect(status().isOk());
        mvc.perform(post("/ratings").with(as(tenant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":\"" + job + "\",\"score\":5}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/ratings").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":\"" + job + "\",\"score\":5,\"comment\":\"Puntual\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/fixers/" + fixerId + "/profile").with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ratingCount").value(1));
        mvc.perform(get("/ratings/fixers/" + fixerId).with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviews[0].comment").value("Puntual"));
        mvc.perform(get("/properties/" + property + "/listing").with(as(tenant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seal.granted").value(true));
    }

    @Test
    void quotedFixerCompletesAssessmentVisitAndWholeChecklist() throws Exception {
        String owner = "auth0|recovered-visit-owner";
        String fixer = "auth0|recovered-visit-fixer";
        UUID ownerId = provision(owner, "OWNER");
        UUID fixerId = verifiedFixer(fixer, "Bogotá", "PLUMBING");
        UUID request = request(owner, fixtureProperty(ownerId), "Revisar cocina");
        quote(fixer, request, 900_000);
        JsonNode visit = json(mvc.perform(post("/visits").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + request + "\",\"fixerUserId\":\"" + fixerId
                                + "\",\"kind\":\"KITCHEN_ASSESSMENT\",\"scheduledAt\":\"" + tomorrow(10) + "\"}"))
                .andExpect(status().isCreated()));
        UUID visitId = UUID.fromString(visit.get("id").asText());
        assertThat(visit.get("checklist")).hasSize(7);
        mvc.perform(post("/visits/" + visitId + "/confirm").with(as(fixer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        jdbc.update("UPDATE technical_visits SET scheduled_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), visitId);
        StringBuilder checklist = new StringBuilder("[");
        for (int position = 0; position < 7; position++) checklist.append(position == 0 ? "" : ",")
                .append("{\"position\":").append(position).append(",\"result\":\"OK\"}");
        checklist.append(']');
        mvc.perform(post("/visits/" + visitId + "/complete").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"Evaluación completa\",\"checklist\":" + checklist + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void preRentalProjectPublishesOnlyAfterEveryDigitalRequirementCompletes() throws Exception {
        String owner = "auth0|recovered-prerental-owner";
        String plumber = "auth0|recovered-prerental-plumber";
        String electrician = "auth0|recovered-prerental-electrician";
        UUID ownerId = provision(owner, "OWNER");
        verifiedFixer(plumber, "Bogotá", "PLUMBING");
        verifiedFixer(electrician, "Bogotá", "ELECTRICAL");
        UUID property = createProperty(owner, false, "Casa prearriendo");
        JsonNode project = json(mvc.perform(post("/remodelings").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyId\":\"" + property + "\",\"title\":\"Lista para arrendar\",\"goal\":\"PRE_RENTAL\",\"targetRent\":2800000,\"requirements\":[{\"title\":\"Fuga\",\"description\":\"Fuga de agua\",\"mediaIds\":[]},{\"title\":\"Tablero\",\"description\":\"Cableado eléctrico\",\"mediaIds\":[]}]}"))
                .andExpect(status().isCreated()));
        UUID projectId = UUID.fromString(project.get("id").asText());
        mvc.perform(post("/remodelings/" + projectId + "/publish-listing").with(as(owner))).andExpect(status().isConflict());
        for (JsonNode requirement : project.get("requirements")) {
            String fixer = "PLUMBING".equals(requirement.get("specialty").asText()) ? plumber : electrician;
            UUID request = UUID.fromString(requirement.get("requestId").asText());
            UUID payment = accept(owner, quote(fixer, request, 200_000));
            approve(owner, payment);
            mvc.perform(post("/jobs/" + job(request) + "/complete").with(as(fixer))).andExpect(status().isOk());
        }
        mvc.perform(post("/remodelings/" + projectId + "/publish-listing").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.listingPublishedAt").isNotEmpty())
                .andExpect(jsonPath("$.readyToPublish").value(false));
        mvc.perform(get("/properties/" + property).with(as(owner)))
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.price").value(2800000));
    }

    @Test
    void quotationNotificationsAndVerificationQueueRemainAvailableWithDigitalAcceptance() throws Exception {
        String owner = "auth0|recovered-notify-owner";
        String winner = "auth0|recovered-notify-winner";
        String loser = "auth0|recovered-notify-loser";
        String admin = "auth0|recovered-notify-admin";
        String applicant = "auth0|recovered-notify-applicant";
        UUID ownerId = provision(owner, "OWNER");
        UUID winnerId = verifiedFixer(winner, "Bogotá", "PLUMBING");
        UUID loserId = verifiedFixer(loser, "Bogotá", "PLUMBING");
        provision(admin, "PLATFORM_ADMIN");
        UUID applicantId = provision(applicant, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET submitted_at = CURRENT_TIMESTAMP WHERE user_id = ?", applicantId);
        mvc.perform(get("/fixers/verification/pending").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.fixerUserId == '" + applicantId + "')]").isNotEmpty());
        UUID request = request(owner, fixtureProperty(ownerId), "Fuga de ducha");
        UUID accepted = quote(winner, request, 210_000);
        quote(loser, request, 260_000);
        assertThat(notificationCount(ownerId, "QUOTATION_RECEIVED")).isEqualTo(2);
        UUID payment = accept(owner, accepted);
        approve(owner, payment);
        assertThat(notificationCount(winnerId, "QUOTATION_ACCEPTED")).isEqualTo(1);
        assertThat(notificationCount(loserId, "QUOTATION_REJECTED")).isEqualTo(1);
        mvc.perform(get("/notifications/me").queryParam("type", "QUOTATION_ACCEPTED").with(as(winner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("QUOTATION_ACCEPTED"));
    }

    private RequestPostProcessor as(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test").claim("name", "Recovered test"));
    }

    private UUID provision(String subject, String role) throws Exception {
        JsonNode created = json(mvc.perform(post("/auth/bootstrap").with(as(subject))).andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asText());
        if ("OWNER".equals(role) || "TENANT".equals(role) || "FIXER".equals(role)) {
            mvc.perform(post("/auth/select-role").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isOk());
        } else jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    private UUID verifiedFixer(String subject, String city, String specialty) throws Exception {
        UUID id = provision(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status='VERIFIED', city=? WHERE user_id=?", city, id);
        jdbc.update("INSERT INTO fixer_specialties(fixer_user_id, specialty) VALUES (?, ?)", id, specialty);
        return id;
    }

    private UUID createProperty(String owner, boolean publish, String name) throws Exception {
        JsonNode property = json(mvc.perform(post("/properties").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"Carrera 7 # 45-12\",\"city\":\"Bogotá\",\"areaM2\":72.5,\"latitude\":4.6352,\"longitude\":-74.0648,\"listingType\":\"RENT\",\"propertyType\":\"APARTMENT\",\"price\":2300000,\"adminFee\":280000,\"bedrooms\":3,\"bathrooms\":2,\"parkingSpots\":1,\"stratum\":4,\"neighborhood\":\"Chapinero Alto\",\"description\":\"Apartamento recuperado\",\"publish\":" + publish + "}"))
                .andExpect(status().isCreated()));
        return UUID.fromString(property.get("id").asText());
    }

    private UUID fixtureProperty(UUID ownerId) {
        UUID id = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertPublished(jdbc, id, ownerId);
        return id;
    }

    private UUID request(String actor, UUID property, String title) throws Exception {
        JsonNode created = json(mvc.perform(post("/requests").with(as(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyId\":\"" + property + "\",\"title\":\"" + title + "\",\"description\":\"Reparación necesaria\",\"mediaIds\":[],\"paymentMethod\":\"TRANSFER\"}"))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("requestId").asText());
        jdbc.update("UPDATE repair_requests SET specialty='PLUMBING' WHERE id=?", id);
        return id;
    }

    private UUID quote(String fixer, UUID request, long amount) throws Exception {
        JsonNode quotation = json(mvc.perform(post("/quotations").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + request + "\",\"amount\":" + amount + ",\"estimatedDays\":2,\"message\":\"Oferta recuperada\"}"))
                .andExpect(status().isCreated()));
        return UUID.fromString(quotation.get("id").asText());
    }

    private UUID accept(String actor, UUID quotation) throws Exception {
        JsonNode accepted = json(mvc.perform(post("/quotations/" + quotation + "/accept").with(as(actor))).andExpect(status().isOk()));
        return UUID.fromString(accepted.get("paymentId").asText());
    }

    private void approve(String actor, UUID payment) throws Exception {
        mvc.perform(post("/payments/" + payment + "/simulate").with(as(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private UUID job(UUID request) {
        return jdbc.queryForObject("SELECT id FROM jobs WHERE request_id=?", UUID.class, request);
    }

    private UUID readyEvidence(String fixer) throws Exception {
        JsonNode ticket = json(mvc.perform(post("/media/uploads").with(as(fixer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"JOB_EVIDENCE\",\"contentType\":\"image/jpeg\",\"sizeBytes\":6}"))
                .andExpect(status().isCreated()));
        UUID media = UUID.fromString(ticket.get("mediaId").asText());
        String key = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id=?", String.class, media);
        TestStorageConfiguration.instance().put(key, TestStorageConfiguration.JPEG_MAGIC, "image/jpeg");
        mvc.perform(post("/media/uploads/" + media + "/confirm").with(as(fixer))).andExpect(status().isOk());
        return media;
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String tomorrow(int hour) {
        return ZonedDateTime.now(BOGOTA).plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0).toInstant().toString();
    }

    private long notificationCount(UUID recipient, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id=? AND type=?", Long.class, recipient, type);
    }
}
