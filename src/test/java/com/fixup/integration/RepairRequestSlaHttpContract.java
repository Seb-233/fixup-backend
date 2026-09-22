package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.requests.application.CheckRepairRequestSla;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for FR-UC-08 (urgencia, reloj de SLA y estados de la solicitud).
 *
 * <p>Corre dos veces: contra H2 en la suite rápida ({@code RepairRequestSlaContextTest}) y contra
 * PostgreSQL real por Testcontainers ({@code PostgresRepairRequestSlaIT}), como el resto de los
 * contratos del repositorio.
 *
 * <p>El barrido de SLA se invoca explícitamente en lugar de esperar al @Scheduled: el perfil de
 * pruebas apaga el reloj (fixup.sla.enabled=false), de modo que lo que se afirma aquí es la
 * decisión del barrido y no la puntualidad del planificador.
 */
abstract class RepairRequestSlaHttpContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CheckRepairRequestSla checkSla;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

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

    private UUID seedProperty(UUID ownerId) {
        UUID propertyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at)"
                + " VALUES (?, ?, 'Prop', 'Addr', 'City', 10, NOW(), NOW())",
            propertyId, ownerId);
        return propertyId;
    }

    private static RequestPostProcessor as(String auth0Id, String role) {
        return jwt().jwt(j -> j.subject(auth0Id).claim("roles", role));
    }

    private String openRequest(String auth0Id, UUID propertyId, String urgency) throws Exception {
        String body = """
            {"propertyId": "%s", "title": "Tubo roto en la pared",
             "description": "Fuga de agua masiva en el muro de la cocina",
             "mediaIds": [], "urgency": %s}
            """.formatted(propertyId, urgency == null ? "null" : "\"" + urgency + "\"");
        var response = mvc.perform(post("/requests").with(as(auth0Id, "OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("requestId").asText();
    }

    /**
     * Lleva la solicitud a ASSIGNED escribiendo la fila, no cotizando: el camino de la asignación
     * ya lo cubre el contrato de cotizaciones, y lo que se ejerce aquí son las rutas del ciclo de
     * vida. La restricción ck_request_assignment valida que el estado sembrado sea legal.
     */
    private void assignTo(String requestId, UUID fixerUserId) {
        jdbc.update("UPDATE repair_requests SET status = 'ASSIGNED', assigned_fixer_user_id = ?"
                + " WHERE id = ?", fixerUserId, UUID.fromString(requestId));
    }

    private void setSlaDeadline(String requestId, Instant deadline) {
        jdbc.update("UPDATE repair_requests SET sla_deadline = ? WHERE id = ?",
            Timestamp.from(deadline), UUID.fromString(requestId));
    }

    // ---------- urgencia ----------

    @Test
    void newRequestCarriesUrgencyAndItsSlaDeadline() throws Exception {
        UUID ownerId = seedUser("auth0|sla-owner-1", "OWNER");
        UUID propertyId = seedProperty(ownerId);

        String body = """
            {"propertyId": "%s", "title": "Tubo roto", "description": "Fuga de agua masiva",
             "mediaIds": [], "urgency": "URGENT"}
            """.formatted(propertyId);

        mvc.perform(post("/requests").with(as("auth0|sla-owner-1", "OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.urgency").value("URGENT"))
            .andExpect(jsonPath("$.slaDeadline").isNotEmpty());
    }

    @Test
    void aRequestOpenedWithoutUrgencyDefaultsToMedium() throws Exception {
        UUID ownerId = seedUser("auth0|sla-owner-default", "OWNER");
        String requestId = openRequest("auth0|sla-owner-default", seedProperty(ownerId), null);
        assertThat(jdbc.queryForObject("SELECT urgency FROM repair_requests WHERE id = ?",
            String.class, UUID.fromString(requestId))).isEqualTo("MEDIUM");
    }

    @Test
    void theOwnerChangesTheUrgencyAndTheDeadlineMoves() throws Exception {
        UUID ownerId = seedUser("auth0|sla-owner-2", "OWNER");
        String requestId = openRequest("auth0|sla-owner-2", seedProperty(ownerId), "LOW");

        var before = jdbc.queryForObject("SELECT sla_deadline FROM repair_requests WHERE id = ?",
            Timestamp.class, UUID.fromString(requestId)).toInstant();

        mvc.perform(patch("/requests/" + requestId + "/urgency").with(as("auth0|sla-owner-2", "OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"urgency\":\"HIGH\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.urgency").value("HIGH"))
            .andExpect(jsonPath("$.slaDeadline").isNotEmpty());

        var after = jdbc.queryForObject("SELECT sla_deadline FROM repair_requests WHERE id = ?",
            Timestamp.class, UUID.fromString(requestId)).toInstant();

        // El plazo se mide desde la apertura: pasar de LOW (7 días) a HIGH (24 h) lo adelanta.
        assertThat(after).isBefore(before);
        var createdAt = jdbc.queryForObject("SELECT created_at FROM repair_requests WHERE id = ?",
            Timestamp.class, UUID.fromString(requestId)).toInstant();
        assertThat(Duration.between(createdAt, after)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aStrangerCannotChangeTheUrgencyOfSomeoneElsesRequest() throws Exception {
        UUID ownerId = seedUser("auth0|sla-owner-3", "OWNER");
        seedUser("auth0|sla-stranger", "OWNER");
        String requestId = openRequest("auth0|sla-owner-3", seedProperty(ownerId), "MEDIUM");

        mvc.perform(patch("/requests/" + requestId + "/urgency").with(as("auth0|sla-stranger", "OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"urgency\":\"URGENT\"}"))
            .andExpect(status().isForbidden());
    }

    // ---------- el barrido de SLA ----------

    @Test
    void theSweepAnnouncesABreachExactlyOnce() throws Exception {
        UUID ownerId = seedUser("auth0|sla-breach", "OWNER");
        String requestId = openRequest("auth0|sla-breach", seedProperty(ownerId), "MEDIUM");
        setSlaDeadline(requestId, Instant.now().minus(Duration.ofHours(2)));

        var first = checkSla.sweep();
        assertThat(first.breaches()).isEqualTo(1);
        assertThat(first.warnings()).isZero();

        // La bitácora vive en la base: un segundo barrido no vuelve a avisar lo mismo.
        assertThat(checkSla.sweep().breaches()).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sla_events_log WHERE request_id = ? AND event_type = 'SLA_BREACH'",
            Integer.class, UUID.fromString(requestId))).isEqualTo(1);
    }

    @Test
    void theSweepWarnsBeforeTheDeadline() throws Exception {
        UUID ownerId = seedUser("auth0|sla-warning", "OWNER");
        String requestId = openRequest("auth0|sla-warning", seedProperty(ownerId), "URGENT");
        // URGENT tiene ventana de 4 h, así que el aviso previo sale con 1 h de margen (el piso).
        setSlaDeadline(requestId, Instant.now().plus(Duration.ofMinutes(30)));

        var result = checkSla.sweep();
        assertThat(result.warnings()).isEqualTo(1);
        assertThat(result.breaches()).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sla_events_log WHERE request_id = ? AND event_type = 'SLA_WARNING'",
            Integer.class, UUID.fromString(requestId))).isEqualTo(1);
    }

    @Test
    void theSweepIgnoresRequestsWithoutADeadlineAndTerminatedOnes() throws Exception {
        UUID ownerId = seedUser("auth0|sla-ignored", "OWNER");

        // Una fila anterior a la migración: urgencia sí, plazo no. No entra al SLA por diseño.
        String legacy = openRequest("auth0|sla-ignored", seedProperty(ownerId), "MEDIUM");
        jdbc.update("UPDATE repair_requests SET sla_deadline = NULL WHERE id = ?",
            UUID.fromString(legacy));

        // Una solicitud cancelada ya no debe nada aunque su plazo esté vencido.
        String cancelled = openRequest("auth0|sla-ignored", seedProperty(ownerId), "MEDIUM");
        setSlaDeadline(cancelled, Instant.now().minus(Duration.ofDays(1)));
        mvc.perform(post("/requests/" + cancelled + "/cancel").with(as("auth0|sla-ignored", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        var result = checkSla.sweep();
        assertThat(result.breaches()).isZero();
        assertThat(result.warnings()).isZero();
    }

    // ---------- ciclo de vida ----------

    @Test
    void theAssignedFixerWalksTheRequestThroughItsLifecycle() throws Exception {
        UUID ownerId = seedUser("auth0|sla-life-owner", "OWNER");
        UUID fixerId = seedUser("auth0|sla-life-fixer", "FIXER");
        String requestId = openRequest("auth0|sla-life-owner", seedProperty(ownerId), "MEDIUM");
        assignTo(requestId, fixerId);

        mvc.perform(post("/requests/" + requestId + "/start").with(as("auth0|sla-life-fixer", "FIXER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mvc.perform(post("/requests/" + requestId + "/hold").with(as("auth0|sla-life-fixer", "FIXER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ON_HOLD"));

        // La pausa la levanta también el propietario: la causa puede estar de cualquier lado.
        mvc.perform(post("/requests/" + requestId + "/resume").with(as("auth0|sla-life-owner", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void aFixerWhoWasNotAssignedCannotStartTheWork() throws Exception {
        UUID ownerId = seedUser("auth0|sla-other-owner", "OWNER");
        UUID fixerId = seedUser("auth0|sla-assigned-fixer", "FIXER");
        seedUser("auth0|sla-other-fixer", "FIXER");
        String requestId = openRequest("auth0|sla-other-owner", seedProperty(ownerId), "MEDIUM");
        assignTo(requestId, fixerId);

        mvc.perform(post("/requests/" + requestId + "/start").with(as("auth0|sla-other-fixer", "FIXER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void startingARequestThatNobodyTookIsAConflict() throws Exception {
        UUID ownerId = seedUser("auth0|sla-open-owner", "OWNER");
        seedUser("auth0|sla-open-fixer", "FIXER");
        String requestId = openRequest("auth0|sla-open-owner", seedProperty(ownerId), "MEDIUM");

        mvc.perform(post("/requests/" + requestId + "/start").with(as("auth0|sla-open-fixer", "FIXER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void onlyTheOwnerCancelsAndNeverTwice() throws Exception {
        UUID ownerId = seedUser("auth0|sla-cancel-owner", "OWNER");
        seedUser("auth0|sla-cancel-stranger", "OWNER");
        String requestId = openRequest("auth0|sla-cancel-owner", seedProperty(ownerId), "MEDIUM");

        mvc.perform(post("/requests/" + requestId + "/cancel").with(as("auth0|sla-cancel-stranger", "OWNER")))
            .andExpect(status().isForbidden());

        mvc.perform(post("/requests/" + requestId + "/cancel").with(as("auth0|sla-cancel-owner", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(post("/requests/" + requestId + "/cancel").with(as("auth0|sla-cancel-owner", "OWNER")))
            .andExpect(status().isConflict());
    }

    @Test
    void resumingARequestThatIsNotOnHoldIsAConflict() throws Exception {
        UUID ownerId = seedUser("auth0|sla-resume-owner", "OWNER");
        UUID fixerId = seedUser("auth0|sla-resume-fixer", "FIXER");
        String requestId = openRequest("auth0|sla-resume-owner", seedProperty(ownerId), "MEDIUM");
        assignTo(requestId, fixerId);

        mvc.perform(post("/requests/" + requestId + "/resume").with(as("auth0|sla-resume-fixer", "FIXER")))
            .andExpect(status().isConflict());
    }

    // ---------- el cierre tiene una sola puerta ----------

    private org.springframework.test.web.servlet.request.RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }

    private UUID provisionWithRole(String subject, String role) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"%s\"}".formatted(role)))
                .andExpect(status().isOk());
        return id;
    }

    /**
     * FR-UC-08 + FR-UC-20: cerrar el trabajo cierra la solicitud. Es la prueba de la decisión de
     * integración: no hay POST /requests/{id}/complete, porque el cierre que libera el dinero
     * retenido y el cierre del estado de la solicitud son el mismo hecho. Si se separaran, una
     * ruta movería el estado sin liberar el escrow y la otra liberaría el dinero dejando la
     * solicitud asignada para siempre.
     */
    @Test
    void closingTheJobAlsoClosesTheRepairRequest() throws Exception {
        UUID ownerId = provisionWithRole("auth0|door-owner", "OWNER");
        UUID fixerId = provisionWithRole("auth0|door-fixer", "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixerId);
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, 'PLUMBING')", fixerId);

        UUID propertyId = seedProperty(ownerId);
        String body = """
            {"propertyId":"%s","title":"Tubo roto","description":"Fuga constante de agua en la cocina",
             "mediaIds":[],"urgency":"HIGH"}
            """.formatted(propertyId);
        var created = mvc.perform(post("/requests").with(identity("auth0|door-owner"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(mapper.readTree(created).get("requestId").asText());

        var quotation = mvc.perform(post("/quotations").with(identity("auth0|door-fixer"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","amount":450000,"estimatedDays":3,
                     "message":"Cambio del tramo dañado y prueba de presión"}
                    """.formatted(requestId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID quotationId = UUID.fromString(mapper.readTree(quotation).get("id").asText());

        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity("auth0|door-owner")))
            .andExpect(status().isOk());

        // Aceptar la cotización asigna la solicitud y abre el trabajo.
        mvc.perform(get("/requests/" + requestId).with(identity("auth0|door-owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ASSIGNED"));

        var jobs = mvc.perform(get("/jobs/me").with(identity("auth0|door-fixer")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(mapper.readTree(jobs).get(0).get("id").asText());

        mvc.perform(post("/jobs/" + jobId + "/complete").with(identity("auth0|door-fixer")))
            .andExpect(status().isOk());

        // La solicitud quedó cerrada por el mismo acto, sin haber pasado por /start.
        mvc.perform(get("/requests/" + requestId).with(identity("auth0|door-owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Y el dinero se liberó: el cierre es uno solo, no dos caminos que puedan desincronizarse.
        assertThat(jdbc.queryForObject("SELECT status FROM fixer_earnings WHERE fixer_user_id = ?",
            String.class, fixerId)).isEqualTo("AVAILABLE");

        // Una solicitud ya cerrada no vuelve al SLA.
        setSlaDeadline(requestId.toString(), Instant.now().minus(Duration.ofDays(1)));
        assertThat(checkSla.sweep().breaches()).isZero();
    }
}
