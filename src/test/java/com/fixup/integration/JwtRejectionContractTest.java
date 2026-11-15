package com.fixup.integration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ASR-SEC-08: any invalid JWT is answered 401 UNAUTHENTICATED on every protected endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtRejectionContractTest {
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

    static Stream<Arguments> invalidTokens() throws Exception {
        String sub = "auth0|a1";
        String issuer = TestJwtConfiguration.ISSUER;
        String audience = TestJwtConfiguration.AUDIENCE;
        var key = TestJwtConfiguration.KEY;
        Instant now = Instant.now();
        Instant future = now.plusSeconds(300);
        Instant past = now.minusSeconds(5);
        String plain = new PlainJWT(new JWTClaimsSet.Builder().subject(sub).issuer(issuer)
                .audience(audience).expirationTime(Date.from(future)).build()).serialize();
        return Stream.of(
                arguments("firma de otra clave",
                        TestJwtConfiguration.token(sub, issuer, audience, future, past, TestJwtConfiguration.OTHER_KEY)),
                arguments("audiencia incorrecta",
                        TestJwtConfiguration.token(sub, issuer, "https://other.example.test", future, past, key)),
                arguments("emisor incorrecto",
                        TestJwtConfiguration.token(sub, "https://evil.example.test/", audience, future, past, key)),
                arguments("vencido",
                        TestJwtConfiguration.token(sub, issuer, audience, now.minusSeconds(3600),
                                now.minusSeconds(7200), key)),
                arguments("aun no valido (nbf futuro)",
                        TestJwtConfiguration.token(sub, issuer, audience, now.plusSeconds(7200),
                                now.plusSeconds(3600), key)),
                arguments("sin exp",
                        TestJwtConfiguration.token(sub, issuer, audience, null, past, key)),
                arguments("sub vacio",
                        TestJwtConfiguration.token("", issuer, audience, future, past, key)),
                arguments("sub de 256 caracteres",
                        TestJwtConfiguration.token("a".repeat(256), issuer, audience, future, past, key)),
                arguments("algoritmo none (sin firma)", plain),
                arguments("cadena que no es un JWT", "esto-no-es-un-jwt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    void invalidTokenIsRejectedWith401(String caso, String token) throws Exception {
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    static Stream<MockHttpServletRequestBuilder> protectedEndpoints() {
        return Stream.of(get("/auth/me"), post("/auth/bootstrap"),
                patch("/notifications/read-all"), post("/payments/me/payouts"));
    }

    @ParameterizedTest
    @MethodSource("protectedEndpoints")
    void tokenSignedWithForeignKeyIsRejectedOnEveryProtectedEndpoint(MockHttpServletRequestBuilder request)
            throws Exception {
        String forged = TestJwtConfiguration.token("auth0|a1", TestJwtConfiguration.ISSUER,
                TestJwtConfiguration.AUDIENCE, Instant.now().plusSeconds(300),
                Instant.now().minusSeconds(5), TestJwtConfiguration.OTHER_KEY);
        mvc.perform(request.header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void validTokenIsAcceptedSoTheRejectionsAboveAreDueToTheToken() throws Exception {
        mvc.perform(get("/auth/me").header("Authorization",
                        "Bearer " + TestJwtConfiguration.token("auth0|a1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NOT_PROVISIONED"));
    }

    @Test
    void missingOrNonBearerCredentialsAreRejectedWith401() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }
}
