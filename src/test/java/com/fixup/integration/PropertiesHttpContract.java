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


    /**
     * FR-UC-25: pedir un recurso ajeno se rechaza siempre, pero el código difiere por base de
     * datos y eso es la política haciendo su trabajo. Contra H2 la consulta devuelve la fila y la
     * capa de aplicación responde 403. Contra PostgreSQL la política RLS la oculta antes, el
     * repositorio no encuentra nada y la respuesta es 404, que además filtra menos. Fijar aquí un
     * único número obligaría a apagar RLS o a debilitar la aserción; el contrato dice lo que cada
     * motor garantiza de verdad.
     */
    protected int foreignResourceStatus() {
        return 403;
    }

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

    /** Crea un inmueble por la API real, para que la política RLS de INSERT también se ejerza. */
    private String createProperty(String auth0Id, String name) throws Exception {
        String json = """
            {"name": "%s", "address": "123 Main St", "city": "Bogota", "areaM2": 150.50}
            """.formatted(name);
        String response = mvc.perform(post("/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asText();
    }

    private static String publicationBody(String title) {
        return """
            {"type": "APARTMENT", "title": "%s", "description": "Luminoso y central",
             "zone": "Chapinero", "monthlyRentSuggestion": 2500000.00}
            """.formatted(title);
    }

    @Test
    void publishesAndUnlistsProperty() throws Exception {
        UUID ownerId = UUID.randomUUID();
        seedUser(ownerId, "auth0|publisher");
        String propertyId = createProperty("auth0|publisher", "Apto 302");

        // FR-UC-12: publicar es una transición sobre el inmueble que ya existe.
        mvc.perform(post("/properties/" + propertyId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationBody("Apartamento en Chapinero"))
                .with(jwt().jwt(j -> j.subject("auth0|publisher").claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.title").value("Apartamento en Chapinero"))
            .andExpect(jsonPath("$.zone").value("Chapinero"))
            .andExpect(jsonPath("$.monthlyRentSuggestion").value(2500000.00))
            .andExpect(jsonPath("$.publishedAt").isNotEmpty())
            .andExpect(jsonPath("$.unlistedAt").isEmpty());

        // Retirar la oferta conserva la fecha de publicación original.
        mvc.perform(post("/properties/" + propertyId + "/unlist")
                .with(jwt().jwt(j -> j.subject("auth0|publisher").claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UNLISTED"))
            .andExpect(jsonPath("$.publishedAt").isNotEmpty())
            .andExpect(jsonPath("$.unlistedAt").isNotEmpty());
    }

    @Test
    void rejectsPublishingAnAlreadyPublishedProperty() throws Exception {
        UUID ownerId = UUID.randomUUID();
        seedUser(ownerId, "auth0|twice");
        String propertyId = createProperty("auth0|twice", "Apto 401");

        mvc.perform(post("/properties/" + propertyId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationBody("Primera publicacion"))
                .with(jwt().jwt(j -> j.subject("auth0|twice").claim("roles", "OWNER"))))
            .andExpect(status().isOk());

        mvc.perform(post("/properties/" + propertyId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationBody("Segunda publicacion"))
                .with(jwt().jwt(j -> j.subject("auth0|twice").claim("roles", "OWNER"))))
            .andExpect(status().isConflict());
    }

    @Test
    void rejectsUnlistingAPropertyThatWasNeverPublished() throws Exception {
        UUID ownerId = UUID.randomUUID();
        seedUser(ownerId, "auth0|draftonly");
        String propertyId = createProperty("auth0|draftonly", "Apto 500");

        mvc.perform(post("/properties/" + propertyId + "/unlist")
                .with(jwt().jwt(j -> j.subject("auth0|draftonly").claim("roles", "OWNER"))))
            .andExpect(status().isConflict());
    }

    @Test
    void rejectsPublicationByAnotherOwner() throws Exception {
        UUID owner1Id = UUID.randomUUID();
        seedUser(owner1Id, "auth0|owner-a");
        UUID owner2Id = UUID.randomUUID();
        seedUser(owner2Id, "auth0|owner-b");

        String propertyId = createProperty("auth0|owner-a", "Casa del dueno A");

        // FR-UC-25: publicar lo ajeno se rechaza aunque quien llame sea un OWNER activo.
        mvc.perform(post("/properties/" + propertyId + "/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(publicationBody("Publicacion ajena"))
                .with(jwt().jwt(j -> j.subject("auth0|owner-b").claim("roles", "OWNER"))))
            .andExpect(status().is(foreignResourceStatus()));
    }

    @Test
    void publishesABatchOfProperties() throws Exception {
        UUID ownerId = UUID.randomUUID();
        seedUser(ownerId, "auth0|batch");
        String first = createProperty("auth0|batch", "Apto 1");
        String second = createProperty("auth0|batch", "Apto 2");

        String body = """
            {"properties": [
              {"propertyId": "%s", "type": "APARTMENT", "title": "Apto uno",
               "zone": "Chapinero", "monthlyRentSuggestion": 1800000.00},
              {"propertyId": "%s", "type": "STUDIO", "title": "Apto dos",
               "zone": "Usaquen", "monthlyRentSuggestion": 1200000.00}
            ]}
            """.formatted(first, second);

        mvc.perform(post("/properties/publish-batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(jwt().jwt(j -> j.subject("auth0|batch").claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publishedCount").value(2))
            .andExpect(jsonPath("$.published", hasSize(2)))
            .andExpect(jsonPath("$.published[0].status").value("PUBLISHED"))
            .andExpect(jsonPath("$.published[1].status").value("PUBLISHED"));

        mvc.perform(get("/properties/" + second)
                .with(jwt().jwt(j -> j.subject("auth0|batch").claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.type").value("STUDIO"));
    }

    @Test
    void rejectsBatchPublicationContainingAPropertyOfAnotherOwner() throws Exception {
        UUID owner1Id = UUID.randomUUID();
        seedUser(owner1Id, "auth0|batch-a");
        UUID owner2Id = UUID.randomUUID();
        seedUser(owner2Id, "auth0|batch-b");

        String mine = createProperty("auth0|batch-b", "Mio");
        String theirs = createProperty("auth0|batch-a", "Ajeno");

        String body = """
            {"properties": [
              {"propertyId": "%s", "type": "APARTMENT", "title": "Mio",
               "zone": "Chapinero", "monthlyRentSuggestion": 1800000.00},
              {"propertyId": "%s", "type": "APARTMENT", "title": "Ajeno",
               "zone": "Usaquen", "monthlyRentSuggestion": 1200000.00}
            ]}
            """.formatted(mine, theirs);

        mvc.perform(post("/properties/publish-batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(jwt().jwt(j -> j.subject("auth0|batch-b").claim("roles", "OWNER"))))
            .andExpect(status().is(foreignResourceStatus()));

        // El lote es atómico: el inmueble propio tampoco quedó publicado.
        mvc.perform(get("/properties/" + mine)
                .with(jwt().jwt(j -> j.subject("auth0|batch-b").claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
