package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        // Closed loopback port makes the Auth0 failure deterministic before the demo fallback.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "fixup.media.deletion.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@DirtiesContext
@Testcontainers
@Import(TestStorageConfiguration.class)
class DemoSecurityContextTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void demoContextSeedsUsersAndLocalTokenAuthenticatesAgainstTheirStoredRoles() throws Exception {
        for (String bean : new String[]{"demoRsaKeys", "demoDualJwtDecoder", "demoTokenEndpoint",
                "demoUsersBootstrapRunner", "jwtDecoder"}) {
            assertThat(context.containsBean(bean)).as(bean).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE auth0_subject LIKE 'auth0|demo-%'",
                Integer.class)).isEqualTo(6);
        // El perfil demo carga los datos de Colombia: sus avisos reales reemplazan a los tres de muestra.
        assertThat(jdbc.queryForList("SELECT DISTINCT type FROM notifications", String.class))
                .contains("CONTRACT_EXPIRING_30D", "QUOTATION_RECEIVED", "PAYMENT_APPROVED", "JOB_COMPLETED");
        var response = mvc.perform(get("/demo/token/OWNER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://demo.fixup.local/"))
                .andReturn().getResponse();
        String token = mapper.readTree(response.getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("OWNER"));
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void seededJobsHaveCanonicalDigitalFundingAndPayoutComposition() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_requests WHERE payment_method <> 'TRANSFER' OR legacy_cash",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs j LEFT JOIN repair_payments p ON p.quotation_id=j.quotation_id "
                + "AND p.purpose='SERVICE' WHERE p.id IS NULL OR p.status <> 'APPROVED' OR j.payment_method <> 'TRANSFER' "
                + "OR j.agreed_amount <> p.amount OR j.owner_user_id <> p.owner_user_id OR j.fixer_user_id <> p.fixer_user_id",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_earnings e JOIN repair_payments p ON p.quotation_id=e.quotation_id "
                + "JOIN jobs j ON j.quotation_id=e.quotation_id WHERE e.created_at <> p.completed_at OR p.completed_at > j.created_at "
                + "OR e.gross_amount <> p.amount OR e.commission_amount <> e.gross_amount / 10 "
                + "OR e.net_amount <> e.gross_amount-e.commission_amount "
                + "OR (j.status='COMPLETED' AND (e.status='HELD' OR e.released_at IS DISTINCT FROM j.completed_at)) "
                + "OR (j.status<>'COMPLETED' AND e.status<>'HELD')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE navigate_to LIKE '%Raw object%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payouts p WHERE p.operation_key IS NULL OR p.status <> 'TRANSFERRED' "
                + "OR p.amount <> (SELECT coalesce(sum(d.net_amount),0) FROM payout_entries d WHERE d.payout_id=p.id) "
                + "OR p.earning_count <> (SELECT count(*) FROM payout_entries d WHERE d.payout_id=p.id)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_entries d JOIN fixer_earnings e ON e.id=d.earning_id "
                + "JOIN payouts p ON p.id=d.payout_id WHERE e.status <> 'PAID_OUT' OR e.paid_out_at <> p.transferred_at "
                + "OR d.fixer_user_id <> e.fixer_user_id OR d.net_amount <> e.net_amount", Integer.class)).isZero();
    }

    /** Restores the non-financial integrity intent of the archived SeedDataContextTest. */
    @Test
    void demoDatasetKeepsWorkHistoryRatingsVisitsAndStoredMediaConsistent() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM properties WHERE status='PUBLISHED'", Integer.class))
                .isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_requests", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM technical_visits", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_ratings r JOIN jobs j ON j.id=r.job_id "
                + "WHERE j.status <> 'COMPLETED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_profiles f WHERE f.rating_count <> "
                + "(SELECT count(*) FROM service_ratings r WHERE r.fixer_user_id=f.user_id)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs j WHERE j.status='COMPLETED' AND NOT EXISTS "
                + "(SELECT 1 FROM property_work_history h WHERE h.job_id=j.id)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM technical_visits v JOIN repair_requests r ON r.id=v.request_id "
                + "WHERE v.status IN ('PROPOSED','CONFIRMED') AND r.status IN ('COMPLETED','CANCELLED')", Integer.class))
                .isZero();
        var keys = jdbc.queryForList("SELECT object_key FROM media_assets WHERE object_key LIKE 'seed/%'", String.class);
        assertThat(keys).isNotEmpty();
        assertThat(keys).allMatch(key -> TestStorageConfiguration.instance().exists(key));
    }

    @Test
    void demoDatasetIncludesRecentDigitalActivityWithoutLegacyCashRecords() {
        String recentActivity = "title IN ("
                + "'Fuga reciente bajo el lavaplatos',"
                + "'Tablero listo para visita programada',"
                + "'Retoque terminado antes de mostrar el inmueble',"
                + "'Seguimiento administrativo de reparación prioritaria',"
                + "'Ajuste de puertas solicitado por arrendatario',"
                + "'Revisión previa a remodelación')";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_requests WHERE " + recentActivity,
                Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_requests WHERE " + recentActivity
                + " AND (payment_method <> 'TRANSFER' OR legacy_cash)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs j JOIN repair_requests r ON r.id=j.request_id WHERE "
                + recentActivity + " AND NOT EXISTS (SELECT 1 FROM repair_payments p WHERE p.quotation_id=j.quotation_id "
                + "AND p.purpose='SERVICE' AND p.status='APPROVED' AND p.payment_method='TRANSFER')", Integer.class))
                .isZero();
    }

    @Test
    void demoHealthStillHidesDetailsAndMetricsRemainDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
