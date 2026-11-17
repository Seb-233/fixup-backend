package com.fixup.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAF-01 con la configuración por defecto: la conciliación apagada hasta aprovisionar su lector.
 * La consulta manual dice que está apagada (503 sin Retry-After), nunca "cuadra", y la autorización
 * se revisa antes: quien no es PLATFORM_ADMIN no se entera de si está encendida.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
class LedgerReconciliationDisabledContextTest {
    private static final String ADMIN = "auth0|ledger-admin-disabled";
    private static final String OWNER = "auth0|ledger-owner-disabled";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
        TestStorageConfiguration.instance().clear();
    }

    @Test
    void turnedOffByDefaultItSaysSoInsteadOfReportingABalance() throws Exception {
        UUID admin = provision(ADMIN);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", admin);
        provision(OWNER);
        mvc.perform(post("/auth/select-role").with(identity(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/administration/payments/reconciliation"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/administration/payments/reconciliation").with(identity(OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("LEDGER_RECONCILIATION_DISABLED")));
        mvc.perform(get("/administration/payments/reconciliation").with(identity(ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LEDGER_RECONCILIATION_DISABLED"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.consistent").doesNotExist())
                .andExpect(jsonPath("$.totals").doesNotExist());
    }

    private UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }
}
