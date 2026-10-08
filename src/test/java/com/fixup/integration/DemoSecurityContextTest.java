package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fixup-security-demo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        // Closed loopback port makes the Auth0 failure deterministic before the demo fallback.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "fixup.media.deletion.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@DirtiesContext
class DemoSecurityContextTest {
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
        var response = mvc.perform(get("/demo/token/OWNER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://demo.fixup.local/"))
                .andReturn().getResponse();
        String token = mapper.readTree(response.getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("OWNER"));
    }

    @Test
    void demoHealthStillHidesDetailsAndMetricsRemainDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
