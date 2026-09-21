package com.fixup.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        jdbc.update("INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", propId, ownerId);

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
    void foreignPropertyReturns404() throws Exception {
        UUID ownerId1 = provisionOwner("auth0|owner-foreign-1");
        UUID ownerId2 = provisionOwner("auth0|owner-foreign-2");
        UUID propId = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", propId, ownerId2);

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
        jdbc.update("INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", propId, ownerId);

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