package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class PropertiesHttpContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @AfterEach
    void clearDatabase() {
        jdbc.update("DELETE FROM properties");
        databaseCleaner.clean();
    }

    private void seedUser(UUID userId, String auth0Id) {
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, 'a@b.com', 'N', 'ACTIVE', NOW(), NOW())",
            userId, auth0Id
        );
        // OWNER = 0 index in role enum if represented as string or ordinal, but we use roles list internally, 
        // wait, I don't need to seed the user's role in DB for the JWT to contain the role, the JWT auth in tests provides authorities.
        // Actually the backend uses the user_roles table or the CurrentActorProvider loads roles from DB.
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'OWNER')", userId);
    }

    @Test
    void createsAndReadsProperty() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String auth0Id = "auth0|owner";
        seedUser(ownerId, auth0Id);

        String json = """
            {
              "name": "My Property",
              "address": "123 Main St",
              "city": "Bogota",
              "areaM2": 150.50
            }
            """;

        // Create
        String response = mvc.perform(post("/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("My Property"))
            .andExpect(jsonPath("$.areaM2").value(150.5))
            .andReturn().getResponse().getContentAsString();

        // Extract ID
        String idStr = mapper.readTree(response).get("id").asText();

        // Read (me)
        mvc.perform(get("/properties/me")
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(idStr));

        // Read (by ID)
        mvc.perform(get("/properties/" + idStr)
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("My Property"));

        // Read (by PLATFORM_ADMIN)
        UUID adminId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, 'a@b.com', 'N', 'ACTIVE', NOW(), NOW())",
            adminId, "auth0|admin"
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'PLATFORM_ADMIN')", adminId);

        mvc.perform(get("/properties/" + idStr)
                .with(jwt().jwt(j -> j.subject("auth0|admin").claim("roles", "PLATFORM_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("My Property"));
    }

    @Test
    void rejectsCreationByNonOwner() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String auth0Id = "auth0|tenant";
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, 'a@b.com', 'N', 'ACTIVE', NOW(), NOW())",
            tenantId, auth0Id
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'TENANT')", tenantId);

        String json = """
            {
              "name": "T Property",
              "address": "123 Main St",
              "city": "Bogota",
              "areaM2": 150.50
            }
            """;

        mvc.perform(post("/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "TENANT"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsReadByOtherOwner() throws Exception {
        UUID owner1Id = UUID.randomUUID();
        seedUser(owner1Id, "auth0|owner1");
        UUID owner2Id = UUID.randomUUID();
        seedUser(owner2Id, "auth0|owner2");

        // Insert property directly
        UUID propertyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 10, NOW(), NOW())",
            propertyId, owner1Id
        );

        mvc.perform(get("/properties/" + propertyId)
                .with(jwt().jwt(j -> j.subject("auth0|owner2").claim("roles", "OWNER"))))
            .andExpect(status().isNotFound());
    }
}


