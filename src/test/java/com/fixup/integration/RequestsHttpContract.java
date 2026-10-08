package com.fixup.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.testsupport.PropertyFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

abstract class RequestsHttpContract {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(j -> j.subject(subject));
    }

    private UUID provisionOwner(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private UUID provisionWithRole(String subject, String role) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        if ("OWNER".equals(role) || "FIXER".equals(role)) {
            mvc.perform(post("/auth/select-role").with(identity(subject))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isOk());
        } else {
            jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    @Test
    void ownerCanCreateRequestForOwnValidProperty() throws Exception {
        UUID ownerId = provisionOwner("auth0|owner-requests-1");
        UUID propId = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, propId, ownerId);

        String body = """
                {
                    "propertyId": "%s",
                    "title": "Tubo roto en la pared",
                    "description": "Fuga de agua masiva",
                    "mediaIds": []
                }
                """.formatted(propId);

        mvc.perform(post("/requests").with(identity("auth0|owner-requests-1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").value(propId.toString()))
                .andExpect(jsonPath("$.specialty").value("PLUMBING"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void urgentRequestPersistsExactlyOneOwnerNotification() throws Exception {
        String subject = "auth0|owner-urgent-notification";
        UUID ownerId = provisionOwner(subject);
        UUID propertyId = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, propertyId, ownerId);
        String body = """
                {"propertyId":"%s","title":"Tubo roto urgente","description":"Fuga de agua masiva",
                 "mediaIds":[],"urgencyLevel":"URGENT"}
                """.formatted(propertyId);

        var response = mvc.perform(post("/requests").with(identity(subject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.urgencyLevel").value("URGENT"))
                .andReturn().getResponse();
        UUID requestId = UUID.fromString(mapper.readTree(response.getContentAsString()).get("requestId").asText());

        var notifications = jdbc.queryForList("""
                SELECT type, title, body, navigate_to, entity_id, entity_type,
                       data->>'urgency' AS urgency, read_at, created_at
                FROM notifications WHERE recipient_user_id = ?
                """, ownerId);
        assertThat(notifications).hasSize(1);
        var notification = notifications.getFirst();
        assertThat(notification.get("type")).isEqualTo("REQUEST_CREATED_URGENT");
        assertThat(notification.get("title")).isEqualTo("Solicitud urgente creada: Tubo roto urgente");
        assertThat(notification.get("body")).isEqualTo(
                "Tu solicitud urgente fue recibida y asignada a la cola de prioridad. Recibirás propuestas de técnicos pronto. SLA máximo: 48h.");
        assertThat(notification.get("navigate_to")).isEqualTo("/requests/" + requestId);
        assertThat(notification.get("entity_id")).isEqualTo(requestId);
        assertThat(notification.get("entity_type")).isEqualTo("REQUEST");
        assertThat(notification.get("urgency")).isEqualTo("URGENT");
        assertThat(notification.get("read_at")).isNull();
        assertThat(notification.get("created_at")).isNotNull();
    }

    @Test
    void foreignPropertyReturns404() throws Exception {
        UUID ownerId1 = provisionOwner("auth0|owner-foreign-1");
        UUID ownerId2 = provisionOwner("auth0|owner-foreign-2");
        UUID propId = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, propId, ownerId2);

        String body = """
                {"propertyId":"%s","title":"Fuga","description":"Gotera","mediaIds":[]}
                """.formatted(propId);

        mvc.perform(post("/requests").with(identity("auth0|owner-foreign-1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonexistentPropertyReturns404() throws Exception {
        provisionOwner("auth0|owner-nonexistent");
        String body = """
                {"propertyId":"%s","title":"Fuga","description":"Gotera","mediaIds":[]}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/requests").with(identity("auth0|owner-nonexistent"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenantCannotCreateRequest() throws Exception {
        provisionWithRole("auth0|tenant-1", "TENANT");
        String body = """
                {"propertyId":"%s","title":"Fuga","description":"Gotera","mediaIds":[]}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/requests").with(identity("auth0|tenant-1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void realEstateManagerCannotCreateRequest() throws Exception {
        provisionWithRole("auth0|rem-1", "REAL_ESTATE_MANAGER");
        String body = """
                {"propertyId":"%s","title":"Fuga","description":"Gotera","mediaIds":[]}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/requests").with(identity("auth0|rem-1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void fixerCannotCreateRequest() throws Exception {
        provisionWithRole("auth0|fixer-1", "FIXER");
        String body = """
                {"propertyId":"%s","title":"Fuga","description":"Gotera","mediaIds":[]}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/requests").with(identity("auth0|fixer-1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void inactiveOwnerCannotCreateRequest() throws Exception {
        UUID id = provisionOwner("auth0|inactive-owner");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", id);
        String body = """
                {"propertyId":"%s","title":"Fuga","description":"Gotera","mediaIds":[]}
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/requests").with(identity("auth0|inactive-owner"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacySpecialtyFieldInBodyReturns400() throws Exception {
        UUID ownerId = provisionOwner("auth0|owner-legacy-field");
        UUID propId = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, propId, ownerId);

        String body = """
                {
                    "propertyId": "%s",
                    "title": "Tubo",
                    "description": "Fuga",
                    "mediaIds": [],
                    "specialty": "PLUMBING"
                }
                """.formatted(propId);

        mvc.perform(post("/requests").with(identity("auth0|owner-legacy-field"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingPropertyIdReturns400() throws Exception {
        provisionOwner("auth0|owner-missing-prop");
        String body = """
                {"title":"Fuga","description":"Gotera","mediaIds":[]}
                """;

        mvc.perform(post("/requests").with(identity("auth0|owner-missing-prop"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
