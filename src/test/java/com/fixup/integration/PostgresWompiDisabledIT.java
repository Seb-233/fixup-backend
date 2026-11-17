package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import com.fixup.testsupport.PropertyFixtures;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * INT-01, despliegue con Wompi apagado. El servidor recibe llaves de producción (lo que el alcance
 * académico prohíbe): las rechaza (fail-closed), ningún endpoint de Wompi opera y los pagos existentes
 * siguen con la pasarela simulada. Es el mismo comportamiento que sin llaves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class PostgresWompiDisabledIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("fixup.payments.wompi.public-key", () -> "pub_prod_rejected");
        r.add("fixup.payments.wompi.private-key", () -> "prv_prod_rejected");
        r.add("fixup.payments.wompi.integrity-secret", () -> "prod_integrity_rejected");
        r.add("fixup.payments.wompi.events-secret", () -> "prod_events_rejected");
        // Aunque alguien apunte la API a producción, tampoco se acepta.
        r.add("fixup.payments.wompi.api-base-url", () -> "https://production.wompi.co/v1");
        r.add("fixup.payments.wompi.return-origins", () -> "http://fixup.localhost:4200");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationDatabaseCleaner cleaner;
    private UUID payment;

    @BeforeEach void setup() { cleaner.clean(); }
    @AfterEach void cleanup() { cleaner.clean(); }

    @Test
    void productionKeysLeaveWompiOffAndTheSimulatedGatewayUntouched() throws Exception {
        accepted();
        mvc.perform(get("/payments/wompi").with(identity("owner"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.environment").doesNotExist());
        mvc.perform(post("/payments/" + payment + "/wompi/checkout").with(identity("owner")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"returnUrl\":\"http://fixup.localhost:4200/payments/" + payment + "\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WOMPI_NOT_CONFIGURED"));
        mvc.perform(post("/payments/" + payment + "/wompi/transactions/12077206-1-1").with(identity("owner")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WOMPI_NOT_CONFIGURED"));
        mvc.perform(post("/payments/wompi/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"transaction.updated\",\"environment\":\"prod\"}"))
                .andExpect(status().isUnauthorized());
        provision("admin", "PLATFORM_ADMIN");
        mvc.perform(get("/payments/wompi/transactions").with(identity("admin")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WOMPI_NOT_CONFIGURED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wompi_checkouts", Integer.class)).isZero();

        String reference = jdbc.queryForObject("SELECT reference FROM repair_payments WHERE id = ?", String.class, payment);
        mvc.perform(post("/payments/" + payment + "/attempts").with(identity("owner")).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\",\"amount\":100000,\"instrument\":\"SANDBOX_CARD\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM repair_payments", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(1);
    }

    private void accepted() throws Exception {
        UUID owner = provision("owner", "OWNER");
        UUID fixer = provision("fixer", "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixer);
        jdbc.update("INSERT INTO fixer_specialties(fixer_user_id,specialty) VALUES (?,'PLUMBING')", fixer);
        UUID property = UUID.randomUUID();
        PropertyFixtures.insertPublished(jdbc, property, owner);
        UUID request = uuid(json(mvc.perform(post("/requests").with(identity("owner")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + property + "\",\"title\":\"Fuga\",\"description\":\"Reparar tuberia\",\"mediaIds\":[],\"paymentMethod\":\"TRANSFER\"}"))
                .andExpect(status().isCreated()).andReturn()), "requestId");
        jdbc.update("UPDATE repair_requests SET specialty = 'PLUMBING' WHERE id = ?", request);
        UUID quotation = uuid(json(mvc.perform(post("/quotations").with(identity("fixer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + request + "\",\"amount\":100000,\"estimatedDays\":2,\"message\":\"Reparacion\"}"))
                .andExpect(status().isCreated()).andReturn()), "id");
        payment = uuid(json(mvc.perform(post("/quotations/" + quotation + "/accept").with(identity("owner")))
                .andExpect(status().isOk()).andReturn()), "paymentId");
    }

    private UUID provision(String name, String role) throws Exception {
        UUID id = uuid(json(mvc.perform(post("/auth/bootstrap").with(identity(name))).andExpect(status().isCreated()).andReturn()), "id");
        if (role.equals("PLATFORM_ADMIN")) {
            jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role);
            return id;
        }
        mvc.perform(post("/auth/select-role").with(identity(name)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private RequestPostProcessor identity(String name) {
        return jwt().jwt(t -> t.subject("auth0|wompi-off-" + name).claim("email", name + "@example.test").claim("name", name));
    }
    private JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString()); }
    private UUID uuid(JsonNode j, String key) { return UUID.fromString(j.get(key).asText()); }
}
