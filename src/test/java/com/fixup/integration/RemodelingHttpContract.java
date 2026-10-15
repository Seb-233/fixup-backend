package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class RemodelingHttpContract {
    protected abstract ResultMatcher expectedHiddenProjectStatus();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
        TestStorageConfiguration.instance().clear();
    }

    RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }

    RequestPostProcessor forgedOwnerIdentity(String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("email", subject + "@example.test")
                .claim("roles", List.of("OWNER")));
    }

    UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    UUID provisionWithRole(String subject, String role) throws Exception {
        UUID id = provision(subject);
        if ("OWNER".equals(role) || "TENANT".equals(role) || "FIXER".equals(role)) {
            mvc.perform(post("/auth/select-role").with(identity(subject))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isOk());
        } else {
            jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    UUID provisionOwner(String subject) throws Exception {
        return provisionWithRole(subject, "OWNER");
    }

    UUID provisionAdmin(String subject) throws Exception {
        UUID id = provision(subject);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", id);
        return id;
    }

    UUID provisionVerifiedFixer(String subject, String... specialties) throws Exception {
        UUID id = provisionWithRole(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", id);
        for (String specialty : specialties) {
            jdbc.update("INSERT INTO fixer_specialties(fixer_user_id, specialty) VALUES (?, ?)",
                    id, specialty);
        }
        return id;
    }

    UUID property(UUID ownerId) {
        UUID propertyId = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertPublished(jdbc, propertyId, ownerId);
        return propertyId;
    }

    String twoRequirementBody(UUID propertyId) {
        return """
                {
                  "propertyId":"%s",
                  "title":"Renovación integral",
                  "description":"Cocina y red eléctrica",
                  "requirements":[
                    {"title":"Arreglar fuga","description":"Fuga de agua en la tubería","mediaIds":[],"paymentMethod":"TRANSFER"},
                    {"title":"Cambiar tablero","description":"Cableado eléctrico y breaker","mediaIds":[],"paymentMethod":"CASH"}
                  ]
                }
                """.formatted(propertyId);
    }

    JsonNode createProject(String ownerSubject, UUID propertyId) throws Exception {
        var result = mvc.perform(post("/remodelings").with(identity(ownerSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoRequirementBody(propertyId)))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    UUID submitQuotation(String fixerSubject, UUID requestId, long amount) throws Exception {
        String body = """
                {"requestId":"%s","amount":%d,"estimatedDays":2,"message":"Oferta"}
                """.formatted(requestId, amount);
        var result = mvc.perform(post("/quotations").with(identity(fixerSubject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    void accept(String ownerSubject, UUID quotationId) throws Exception {
        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerSubject)))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCreatesSeveralNormalRequestsWithClassifiedSpecialties() throws Exception {
        String subject = "auth0|remodel-owner-create";
        UUID owner = provisionOwner(subject);
        UUID property = property(owner);

        JsonNode project = createProject(subject, property);

        assertThat(project.get("status").asText()).isEqualTo("OPEN");
        assertThat(project.get("requirements")).hasSize(2);
        assertThat(project.at("/requirements/0/position").asInt()).isZero();
        assertThat(project.at("/requirements/0/specialty").asText()).isEqualTo("PLUMBING");
        assertThat(project.at("/requirements/0/paymentMethod").asText()).isEqualTo("TRANSFER");
        assertThat(project.at("/requirements/1/position").asInt()).isEqualTo(1);
        assertThat(project.at("/requirements/1/specialty").asText()).isEqualTo("ELECTRICAL");
        assertThat(project.at("/requirements/1/paymentMethod").asText()).isEqualTo("CASH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remodeling_projects", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remodeling_requirements", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_requests WHERE property_id = ?",
                Integer.class, property)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "FIXER", "REAL_ESTATE_MANAGER"})
    void nonOwnerRolesCannotCreate(String role) throws Exception {
        String subject = "auth0|remodel-denied-" + role.toLowerCase();
        UUID actor = provisionWithRole(subject, role);
        UUID owner = provisionOwner(subject + "-property-owner");

        mvc.perform(post("/remodelings").with(identity(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoRequirementBody(property(owner))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remodeling_projects", Integer.class))
                .isZero();
    }

    @Test
    void ownerCannotCreateForAnotherOwnersProperty() throws Exception {
        String first = "auth0|remodel-owner-first";
        provisionOwner(first);
        UUID secondOwner = provisionOwner("auth0|remodel-owner-second");

        mvc.perform(post("/remodelings").with(identity(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoRequirementBody(property(secondOwner))))
                .andExpect(status().isNotFound());
    }

    @Test
    void inactiveOwnerAndForgedJwtRoleAreDenied() throws Exception {
        String inactive = "auth0|remodel-owner-inactive";
        UUID inactiveId = provisionOwner(inactive);
        UUID inactiveProperty = property(inactiveId);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", inactiveId);
        mvc.perform(post("/remodelings").with(identity(inactive))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoRequirementBody(inactiveProperty)))
                .andExpect(status().isForbidden());

        String tenant = "auth0|remodel-forged-owner";
        UUID tenantId = provisionWithRole(tenant, "TENANT");
        mvc.perform(post("/remodelings").with(forgedOwnerIdentity(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoRequirementBody(property(tenantId))))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyRequirementsAreRejected() throws Exception {
        String subject = "auth0|remodel-owner-empty";
        UUID owner = provisionOwner(subject);
        String body = """
                {"propertyId":"%s","title":"Vacío","requirements":[]}
                """.formatted(property(owner));
        mvc.perform(post("/remodelings").with(identity(subject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFailureInOneRequirementRollsBackTheWholeProject() throws Exception {
        String subject = "auth0|remodel-owner-rollback";
        UUID owner = provisionOwner(subject);
        UUID property = property(owner);
        String body = """
                {
                  "propertyId":"%s","title":"Rollback",
                  "requirements":[
                    {"title":"Primera","description":"Fuga de agua","mediaIds":[],"paymentMethod":"TRANSFER"},
                    {"title":"Segunda","description":"Cable eléctrico","mediaIds":["%s"],"paymentMethod":"CASH"}
                  ]
                }
                """.formatted(property, UUID.randomUUID());

        mvc.perform(post("/remodelings").with(identity(subject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM remodeling_projects", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_requests WHERE property_id = ?",
                Integer.class, property)).isZero();
    }

    @Test
    void ownerListsAndReadsOwnProjectWhileOthersAndFixersCannot() throws Exception {
        String ownerSubject = "auth0|remodel-owner-private";
        UUID owner = provisionOwner(ownerSubject);
        JsonNode project = createProject(ownerSubject, property(owner));
        UUID projectId = UUID.fromString(project.get("id").asText());

        mvc.perform(get("/remodelings/me").with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(projectId.toString()))
                .andExpect(jsonPath("$[0].progressStatus").value("OPEN"));

        String otherOwner = "auth0|remodel-other-owner";
        provisionOwner(otherOwner);
        mvc.perform(get("/remodelings/" + projectId).with(identity(otherOwner)))
                .andExpect(expectedHiddenProjectStatus());

        String fixer = "auth0|remodel-project-fixer";
        provisionVerifiedFixer(fixer, "PLUMBING");
        mvc.perform(get("/remodelings/" + projectId).with(identity(fixer)))
                .andExpect(expectedHiddenProjectStatus());

        String admin = "auth0|remodel-admin-reader";
        provisionAdmin(admin);
        mvc.perform(get("/remodelings/" + projectId).with(identity(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(owner.toString()));
    }

    @Test
    void independentRequirementsUseTheirOwnPaymentMethodWhenAccepted() throws Exception {
        String ownerSubject = "auth0|remodel-owner-progress";
        UUID owner = provisionOwner(ownerSubject);
        JsonNode project = createProject(ownerSubject, property(owner));
        UUID projectId = UUID.fromString(project.get("id").asText());
        UUID plumbingRequest = UUID.fromString(project.at("/requirements/0/requestId").asText());
        UUID electricalRequest = UUID.fromString(project.at("/requirements/1/requestId").asText());

        String fixer = "auth0|remodel-multi-fixer";
        UUID fixerId = provisionVerifiedFixer(fixer, "PLUMBING", "ELECTRICAL");
        UUID plumbingQuotation = submitQuotation(fixer, plumbingRequest, 100_000);
        UUID electricalQuotation = submitQuotation(fixer, electricalRequest, 200_000);

        accept(ownerSubject, plumbingQuotation);
        assertProjectStatus(ownerSubject, projectId, "PARTIALLY_ASSIGNED", 1, 0);

        accept(ownerSubject, electricalQuotation);
        assertProjectStatus(ownerSubject, projectId, "FULLY_ASSIGNED", 2, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE fixer_user_id = ?",
                Integer.class, fixerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE fixer_user_id = ?",
                Integer.class, fixerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_payments "
                + "WHERE request_id = ? AND purpose = 'SERVICE' AND status = 'PENDING'",
                Integer.class, plumbingRequest)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE request_id = ? AND status = 'ASSIGNED'",
                Integer.class, electricalRequest)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE request_id = ?",
                Integer.class, plumbingRequest)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_payments WHERE request_id = ?",
                Integer.class, electricalRequest)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remodeling_requirements "
                + "WHERE project_id = ? AND request_id IN (?, ?)", Integer.class,
                projectId, plumbingRequest, electricalRequest)).isEqualTo(2);

        UUID transferPayment = jdbc.queryForObject("SELECT id FROM repair_payments WHERE request_id = ?",
                UUID.class, plumbingRequest);
        mvc.perform(post("/payments/" + transferPayment + "/simulate").with(identity(ownerSubject))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE fixer_user_id = ? AND status = 'ASSIGNED'",
                Integer.class, fixerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ? "
                + "AND status = 'HELD'", Integer.class, plumbingQuotation)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings WHERE quotation_id = ?",
                Integer.class, electricalQuotation)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remodeling_requirements "
                + "WHERE project_id = ? AND request_id IN (?, ?)", Integer.class,
                projectId, plumbingRequest, electricalRequest)).isEqualTo(2);

        UUID transferJob = jdbc.queryForObject("SELECT id FROM jobs WHERE request_id = ?",
                UUID.class, plumbingRequest);
        mvc.perform(post("/jobs/" + transferJob + "/complete").with(identity(fixer)))
                .andExpect(status().isOk());
        assertProjectStatus(ownerSubject, projectId, "PARTIALLY_COMPLETED", 2, 1);
        assertThat(jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?",
                String.class, plumbingRequest)).isEqualTo("COMPLETED");

        UUID cashJob = jdbc.queryForObject("SELECT id FROM jobs WHERE request_id = ?",
                UUID.class, electricalRequest);
        mvc.perform(post("/jobs/" + cashJob + "/complete").with(identity(fixer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAmount\":200000}"))
                .andExpect(status().isOk());
        assertProjectStatus(ownerSubject, projectId, "COMPLETED", 2, 2);
        assertThat(jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?",
                String.class, electricalRequest)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_settlements WHERE job_id = ?",
                Integer.class, cashJob)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_debts WHERE quotation_id = ?",
                Integer.class, electricalQuotation)).isZero();
    }

    void assertProjectStatus(String ownerSubject, UUID projectId, String expected,
            int assigned, int completed) throws Exception {
        mvc.perform(get("/remodelings/" + projectId).with(identity(ownerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.assignedCount").value(assigned))
                .andExpect(jsonPath("$.completedCount").value(completed));
    }
}
