package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for FR-UC-10 (bandeja de notificaciones).
 *
 * <p>Corre dos veces: contra H2 ({@code NotificationsContextTest}) y contra PostgreSQL real por
 * Testcontainers ({@code PostgresNotificationsIT}). Contra PostgreSQL además se ejerce de verdad
 * el aislamiento por RLS de V23__notifications_rls, que H2 no implementa.
 *
 * <p>Los escuchas de notificaciones son @Async y corren después del commit, así que la prueba
 * espera de forma acotada en vez de asumir que el aviso ya está escrito. Si nunca llega, falla.
 */
abstract class NotificationsHttpContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);


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
        databaseCleaner.clean();
    }

    private UUID seedUser(String auth0Id, String role) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'N', 'ACTIVE', NOW(), NOW())",
            userId, auth0Id, auth0Id.replace('|', '.') + "@b.com");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role);
        return userId;
    }

    private static RequestPostProcessor as(String auth0Id, String role) {
        return jwt().jwt(j -> j.subject(auth0Id).claim("roles", role));
    }

    /** Siembra un aviso directamente, para las pruebas que ejercen la bandeja y no al emisor. */
    private UUID seedNotification(UUID userId, String type, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, user_id, type, title, message, related_entity_id,"
                + " is_read, created_at, read_at) VALUES (?, ?, ?, ?, 'Mensaje de prueba', NULL, FALSE, NOW(), NULL)",
            id, userId, type, title);
        return id;
    }

    private void awaitNotificationCount(UUID userId, int expected) {
        var deadline = System.nanoTime() + ASYNC_TIMEOUT.toNanos();
        Integer count = null;
        while (System.nanoTime() < deadline) {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?",
                Integer.class, userId);
            if (count != null && count == expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        assertThat(count).as("notificaciones de %s tras esperar %s", userId, ASYNC_TIMEOUT)
            .isEqualTo(expected);
    }

    private String createProperty(String auth0Id) throws Exception {
        var response = mvc.perform(post("/properties").with(as(auth0Id, "OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apto\",\"address\":\"Calle 1\",\"city\":\"Bogota\",\"areaM2\":80}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asText();
    }

    // ---------- el hecho produce el aviso ----------

    @Test
    void publishingAPropertyNotifiesItsOwner() throws Exception {
        UUID ownerId = seedUser("auth0|notif-publisher", "OWNER");
        String propertyId = createProperty("auth0|notif-publisher");

        mvc.perform(post("/properties/" + propertyId + "/publish").with(as("auth0|notif-publisher", "OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"APARTMENT","title":"Apartamento en Chapinero","zone":"Chapinero",
                     "monthlyRentSuggestion":2500000.00}
                    """))
            .andExpect(status().isOk());

        awaitNotificationCount(ownerId, 1);

        mvc.perform(get("/notifications").with(as("auth0|notif-publisher", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].type").value("PROPERTY_PUBLISHED"))
            .andExpect(jsonPath("$[0].relatedEntityId").value(propertyId))
            .andExpect(jsonPath("$[0].read").value(false));
    }

    @Test
    void publishingABatchProducesASingleNotification() throws Exception {
        UUID ownerId = seedUser("auth0|notif-batch", "OWNER");
        String first = createProperty("auth0|notif-batch");
        String second = createProperty("auth0|notif-batch");

        String body = """
            {"properties": [
              {"propertyId":"%s","type":"APARTMENT","title":"Uno","zone":"Chapinero","monthlyRentSuggestion":1800000.00},
              {"propertyId":"%s","type":"STUDIO","title":"Dos","zone":"Usaquen","monthlyRentSuggestion":1200000.00}
            ]}
            """.formatted(first, second);

        mvc.perform(post("/properties/publish-batch").with(as("auth0|notif-batch", "OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        // Un aviso por lote, no uno por inmueble.
        awaitNotificationCount(ownerId, 1);
        mvc.perform(get("/notifications").with(as("auth0|notif-batch", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].type").value("PROPERTY_BATCH_PUBLISHED"));
    }

    // ---------- aislamiento por usuario ----------

    @Test
    void eachUserOnlySeesHisOwnNotifications() throws Exception {
        UUID mine = seedUser("auth0|notif-mine", "OWNER");
        UUID theirs = seedUser("auth0|notif-theirs", "OWNER");
        seedNotification(mine, "PROPERTY_PUBLISHED", "Mi aviso");
        seedNotification(theirs, "PROPERTY_PUBLISHED", "Su aviso");
        seedNotification(theirs, "PROPERTY_UNLISTED", "Su otro aviso");

        mvc.perform(get("/notifications").with(as("auth0|notif-mine", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].title").value("Mi aviso"));

        mvc.perform(get("/notifications").with(as("auth0|notif-theirs", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void theUnreadCountIsPerUser() throws Exception {
        UUID mine = seedUser("auth0|notif-count-mine", "OWNER");
        UUID theirs = seedUser("auth0|notif-count-theirs", "OWNER");
        seedNotification(mine, "PROPERTY_PUBLISHED", "Uno");
        seedNotification(mine, "PROPERTY_UNLISTED", "Dos");
        seedNotification(theirs, "PROPERTY_PUBLISHED", "Ajeno");

        mvc.perform(get("/notifications/unread-count").with(as("auth0|notif-count-mine", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(2));

        mvc.perform(get("/notifications/unread-count").with(as("auth0|notif-count-theirs", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void aNotificationOfAnotherUserCannotBeMarkedAsRead() throws Exception {
        UUID theirs = seedUser("auth0|notif-victim", "OWNER");
        seedUser("auth0|notif-intruder", "OWNER");
        UUID notificationId = seedNotification(theirs, "PROPERTY_PUBLISHED", "Aviso ajeno");

        // El módulo distingue los dos casos a propósito: 404 cuando el aviso no existe y 403
        // cuando existe pero es de otro. El rechazo es igual de firme en ambos.
        mvc.perform(post("/notifications/" + notificationId + "/read")
                .with(as("auth0|notif-intruder", "OWNER")))
            .andExpect(status().is(foreignResourceStatus()));

        // Sigue sin leer para su dueño: el intento ajeno no la tocó.
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?",
            Boolean.class, notificationId)).isFalse();
    }

    // ---------- marcar como leída ----------

    @Test
    void theOwnerMarksOneNotificationAsRead() throws Exception {
        UUID userId = seedUser("auth0|notif-read-one", "OWNER");
        UUID notificationId = seedNotification(userId, "PROPERTY_PUBLISHED", "Aviso");
        seedNotification(userId, "PROPERTY_UNLISTED", "Otro aviso");

        mvc.perform(post("/notifications/" + notificationId + "/read")
                .with(as("auth0|notif-read-one", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.read").value(true))
            .andExpect(jsonPath("$.readAt").isNotEmpty());

        // Por omisión la bandeja devuelve solo las no leídas.
        mvc.perform(get("/notifications").with(as("auth0|notif-read-one", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(get("/notifications").param("includeRead", "true")
                .with(as("auth0|notif-read-one", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void markingAllAsReadOnlyTouchesTheCallersInbox() throws Exception {
        UUID mine = seedUser("auth0|notif-readall-mine", "OWNER");
        UUID theirs = seedUser("auth0|notif-readall-theirs", "OWNER");
        seedNotification(mine, "PROPERTY_PUBLISHED", "Uno");
        seedNotification(mine, "PROPERTY_UNLISTED", "Dos");
        seedNotification(theirs, "PROPERTY_PUBLISHED", "Ajeno");

        mvc.perform(post("/notifications/read-all").with(as("auth0|notif-readall-mine", "OWNER")))
            .andExpect(status().isOk());

        mvc.perform(get("/notifications/unread-count").with(as("auth0|notif-readall-mine", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0));

        // La bandeja del otro usuario quedó intacta.
        mvc.perform(get("/notifications/unread-count").with(as("auth0|notif-readall-theirs", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void anUnknownNotificationIsNotFound() throws Exception {
        seedUser("auth0|notif-unknown", "OWNER");
        mvc.perform(post("/notifications/" + UUID.randomUUID() + "/read")
                .with(as("auth0|notif-unknown", "OWNER")))
            .andExpect(status().isNotFound());
    }
}
