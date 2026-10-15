package com.fixup.integration;

import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityContextTest {
    private static final HttpServer AUTH0_KEYS = jwksServer();

    private static HttpServer jwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] publicKeys = new JWKSet(TestJwtConfiguration.KEY.toPublicJWK()).toString()
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, publicKeys.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(publicKeys);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start test JWKS server", exception);
        }
    }

    @DynamicPropertySource
    static void auth0Keys(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + AUTH0_KEYS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        AUTH0_KEYS.stop(0);
    }

    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void normalContextUsesOnlyAuth0AndDoesNotSeedDemoUsers() {
        assertThat(context.getBeansOfType(JwtDecoder.class)).containsOnlyKeys("jwtDecoder");
        assertThat(context.getBean(JwtDecoder.class)).isInstanceOf(NimbusJwtDecoder.class);
        for (String bean : new String[]{"demoRsaKeys", "demoJwtConfiguration", "demoDualJwtDecoder",
                "demoTokenEndpoint", "demoUsersBootstrapRunner"}) {
            assertThat(context.containsBean(bean)).as(bean).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE auth0_subject LIKE 'auth0|demo-%'",
                Integer.class)).isZero();
    }

    @Test
    void realDecoderAcceptsConfiguredIssuerAndRejectsDemoIssuerEvenWithTrustedSignature() throws Exception {
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + TestJwtConfiguration.token("auth0|a1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_NOT_PROVISIONED"));
        String demoIssuerToken = TestJwtConfiguration.token("auth0|a1", "https://demo.fixup.local/",
                TestJwtConfiguration.AUDIENCE, Instant.now().plusSeconds(300), Instant.now().minusSeconds(5),
                TestJwtConfiguration.KEY);
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + demoIssuerToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/demo/token", "/demo/token/OWNER", "/h2-console", "/h2-console/",
            "/swagger-ui.html", "/v3/api-docs", "/actuator/info", "/actuator/metrics", "/actuator/metrics/jvm.memory.used"})
    void developmentAndAdministrativeEndpointsAreDeniedNormally(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void publicHealthDoesNotRevealComponents() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void corsAllowsCurrentMethodsFromConfiguredOrigin(String method) throws Exception {
        mvc.perform(options("/notifications/read-all").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", method)
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void corsRejectsPatchFromAnUnapprovedOrigin() throws Exception {
        mvc.perform(options("/notifications/read-all").header("Origin", "https://untrusted.example.test")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(patch("/notifications/read-all").with(jwt())
                        .header("Origin", "https://untrusted.example.test"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void allowedCorsDoesNotBypassAuthenticationForPatch() throws Exception {
        mvc.perform(patch("/notifications/read-all").header("Origin", "http://localhost:4200"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }
}
