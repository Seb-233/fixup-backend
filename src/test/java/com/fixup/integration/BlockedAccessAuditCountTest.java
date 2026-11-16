package com.fixup.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ASR-SEC-07 (FR-UC-25): every blocked access leaves exactly one structured audit line, so the
 * number of blocked accesses can be counted from the log. Valid requests leave none.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BlockedAccessAuditCountTest {
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

    @Autowired MockMvc mvc;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    private Logger auditLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAuditAppender() {
        databaseCleaner.clean();
        auditLogger = (Logger) LoggerFactory.getLogger("com.fixup.security.audit");
        auditLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void detachAuditAppender() {
        auditLogger.detachAppender(appender);
        appender.stop();
    }

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", "audit@example.test")
                .claim("name", "Audit user"));
    }

    private List<String> blockedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("blocked_access")).toList();
    }

    @Test
    void everyBlockedAccessIsLoggedExactlyOnceAndValidRequestsLogNothing() throws Exception {
        String subject = "auth0|audit-sec07";
        mvc.perform(post("/auth/bootstrap").with(identity(subject))).andExpect(status().isCreated());

        // Control: a valid request must not leave a blocked_access line.
        mvc.perform(get("/auth/me").with(identity(subject))).andExpect(status().isOk());
        assertThat(blockedLines()).as("valid request must not be audited as blocked").isEmpty();

        int attempts = 5;
        List<String> uris = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String uri = "/contracts/" + UUID.randomUUID();
            uris.add(uri);
            mvc.perform(get(uri).with(identity(subject))).andExpect(result ->
                    assertThat(result.getResponse().getStatus()).as("blocked access must be 403 or 404")
                            .isIn(403, 404));
        }

        var lines = blockedLines();
        System.out.println("ASR-SEC-07 | accesos bloqueados provocados: " + attempts
                + " | lineas de auditoria registradas: " + lines.size());
        lines.forEach(line -> System.out.println("ASR-SEC-07 |   " + line));

        assertThat(lines).hasSize(attempts);
        for (String uri : uris) {
            assertThat(lines).filteredOn(line -> line.contains("uri=" + uri + " ")).hasSize(1);
        }
        assertThat(lines).allSatisfy(line -> {
            assertThat(line).contains("method=GET");
            assertThat(line).containsPattern("reason=(DENIED_OR_NOT_VISIBLE|ACCESS_DENIED|RLS_POLICY_VIOLATION) ");
            assertThat(line).contains("actorUserId=").contains("actorRoles=");
        });
    }
}
