package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.identityaccess.api.AdministrativeRoles;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.application.BootstrapUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Reused unchanged against H2 and PostgreSQL to keep the HTTP/security contract equivalent. */
abstract class IdentityHttpContract {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired BootstrapUser bootstrap;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    @Autowired CurrentActorProvider actors;
    @Autowired AdministrativeRoles administrativeRoles;
    @Autowired FixerEligibility fixerEligibility;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM fixer_profiles");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", "same@example.test")
                .claim("name", "Initial name"));
    }

    UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.roles").isEmpty())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    void authenticate(String subject, String... authorities) {
        var jwt = Jwt.withTokenValue("synthetic-context").header("alg", "RS256").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    void retiredRoutesHaveNoHandlersAndRemainDenied() throws Exception {
        String retiredPrefix = String.join("/", "", "api", "v1");
        var retiredRoutes = List.of(retiredPrefix + "/auth/bootstrap",
                retiredPrefix + "/users/me", retiredPrefix + "/users/me/roles");
        var registered = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()).toList();
        assertThat(registered).contains("/auth/bootstrap", "/auth/me", "/auth/select-role")
                .doesNotContainAnyElementsOf(retiredRoutes);
        for (int index = 0; index < retiredRoutes.size(); index++) {
            String path = retiredRoutes.get(index);
            var request = index == 1 ? get(path) : post(path);
            mvc.perform(request.with(identity("auth0|retired-route")))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void publicHealthHasNoDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void anonymousApiRequestsReturnUniform401() throws Exception {
        for (var request : List.of(get("/auth/me"), post("/auth/bootstrap"),
                post("/auth/select-role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.message").value("Authentication is required"));
        }
    }

    @Test
    void malformedBearerReturns401WithoutLeakingToken() throws Exception {
        mvc.perform(get("/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.path").value("/auth/me"));
    }

    @Test
    void signedValidJwtTraversesRealDecoderAndBootstraps() throws Exception {
        String token = TestJwtConfiguration.token("auth0|signed");
        mvc.perform(post("/auth/bootstrap").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("synthetic@example.test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "future", "no-expiry", "no-subject", "signature"})
    void invalidSignedTokensAreRejected(String defect) throws Exception {
        Instant now = Instant.now();
        String token = TestJwtConfiguration.token(
                defect.equals("no-subject") ? null : "auth0|invalid",
                defect.equals("issuer") ? "https://wrong.example.test/" : TestJwtConfiguration.ISSUER,
                defect.equals("audience") ? "wrong-audience" : TestJwtConfiguration.AUDIENCE,
                defect.equals("no-expiry") ? null : defect.equals("expired") ? now.minusSeconds(300) : now.plusSeconds(300),
                defect.equals("future") ? now.plusSeconds(300) : now.minusSeconds(5),
                defect.equals("signature") ? TestJwtConfiguration.OTHER_KEY : TestJwtConfiguration.KEY);
        mvc.perform(post("/auth/bootstrap").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void repeatedBootstrapKeepsIdentityAndInitialProfile() throws Exception {
        UUID id = provision("auth0|same-subject");
        mvc.perform(post("/auth/bootstrap").with(jwt().jwt(token -> token.subject("auth0|same-subject")
                        .claim("email", "changed@example.test").claim("name", "Changed name"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("same@example.test"))
                .andExpect(jsonPath("$.displayName").value("Initial name"))
                .andExpect(jsonPath("$.roles").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void optionalClaimsAreNotRequiredForBootstrap() throws Exception {
        mvc.perform(post("/auth/bootstrap").with(jwt().jwt(token -> token.subject("auth0|minimal")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.roles").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"roles\":[\"PLATFORM_ADMIN\"]}", "{\"status\":\"ACTIVE\"}",
            "{\"subject\":\"auth0|victim\"}", "{\"id\":\"00000000-0000-0000-0000-000000000001\"}"})
    void bootstrapRejectsClientIdentityAndPrivileges(String body) throws Exception {
        mvc.perform(post("/auth/bootstrap").with(identity("auth0|attacker"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void missingInternalAccountRequiresBootstrap() throws Exception {
        mvc.perform(get("/auth/me").with(identity("auth0|unknown")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_NOT_PROVISIONED"));
    }

    @Test
    void meUsesSubjectAndDoesNotExposeExternalSubject() throws Exception {
        UUID alice = provision("auth0|alice");
        UUID bob = provision("auth0|bob");
        assertThat(alice).isNotEqualTo(bob); // Same email is not an identity key.
        mvc.perform(get("/auth/me").with(identity("auth0|alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(alice.toString()))
                .andExpect(jsonPath("$.externalSubject").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DISABLED"})
    void inactiveUsersAreForbiddenEverywhere(UserStatus accountStatus) throws Exception {
        UUID id = provision("auth0|inactive");
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", accountStatus.name(), id);
        for (var request : List.of(get("/auth/me"), post("/auth/bootstrap"),
                post("/auth/select-role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))) {
            mvc.perform(request.with(identity("auth0|inactive"))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"PLATFORM_ADMIN", "REAL_ESTATE_MANAGER"})
    void cannotSelfAssignAdministrativeRole(Role role) throws Exception {
        provision("auth0|ordinary");
        mvc.perform(post("/auth/select-role").with(identity("auth0|ordinary"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "TENANT", "FIXER"})
    void selfAssignableRolesAreIdempotentAndFixerStartsPending(Role role) throws Exception {
        UUID id = provision("auth0|self-role");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/auth/select-role").with(identity("auth0|self-role"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value(role.name()));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, id))
                .isEqualTo(1);
        if (role == Role.FIXER) {
            assertThat(jdbc.queryForObject("SELECT verification_status FROM fixer_profiles WHERE user_id = ?",
                    String.class, id)).isEqualTo("PENDING");
            var actor = new CurrentActor(id, "auth0|self-role", Set.of(role), UserStatus.ACTIVE);
            assertThatThrownBy(() -> fixerEligibility.requireVerified(actor)).isInstanceOf(FixerNotEligibleException.class);
            jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", id);
            assertThatCode(() -> fixerEligibility.requireVerified(actor)).doesNotThrowAnyException();
            mvc.perform(post("/auth/select-role").with(identity("auth0|self-role"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT verification_status FROM fixer_profiles WHERE user_id = ?",
                    String.class, id)).isEqualTo("VERIFIED");
        } else {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_profiles", Integer.class)).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(value = FixerVerificationStatus.class, names = {"PENDING", "REJECTED", "SUSPENDED"})
    void fixerRoleAloneNeverAuthorizesWork(FixerVerificationStatus verificationStatus) throws Exception {
        UUID id = provision("auth0|fixer");
        mvc.perform(post("/auth/select-role").with(identity("auth0|fixer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}")).andExpect(status().isOk());
        jdbc.update("UPDATE fixer_profiles SET verification_status = ? WHERE user_id = ?", verificationStatus.name(), id);
        var actor = new CurrentActor(id, "auth0|fixer", Set.of(Role.FIXER), UserStatus.ACTIVE);
        assertThatThrownBy(() -> fixerEligibility.requireVerified(actor)).isInstanceOf(FixerNotEligibleException.class);
    }

    @Test
    void clientCannotModifyAnotherUsersRoles() throws Exception {
        UUID attacker = provision("auth0|attacker");
        UUID victim = provision("auth0|victim");
        mvc.perform(post("/auth/select-role").with(identity("auth0|attacker"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\",\"id\":\"" + victim + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/select-role").param("userId", victim.toString()).with(identity("auth0|attacker"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, victim)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, attacker)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"role\":null}", "{\"role\":\"ROOT\"}", "{\"role\":\"owner\"}"})
    void malformedRoleRequestsReturn400(String body) throws Exception {
        provision("auth0|bad-request");
        mvc.perform(post("/auth/select-role").with(identity("auth0|bad-request"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void corsAllowsConfiguredLocalOriginAndRejectsOthers() throws Exception {
        mvc.perform(options("/auth/select-role").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/auth/me").header("Origin", "https://untrusted.example.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void jwtPrivilegesAndAuthoritiesDoNotReplaceDatabaseRoles() throws Exception {
        UUID id = provision("auth0|forged-role");
        mvc.perform(get("/auth/me").with(jwt().jwt(token -> token.subject("auth0|forged-role")
                        .claim("roles", List.of("PLATFORM_ADMIN")).claim("scope", "PLATFORM_ADMIN"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles").isEmpty());
        authenticate("auth0|forged-role", "ROLE_PLATFORM_ADMIN");
        var forgedActor = new CurrentActor(id, "auth0|forged-role", Set.of(Role.PLATFORM_ADMIN), UserStatus.ACTIVE);
        try {
            assertThatThrownBy(() -> administrativeRoles.assignRole(forgedActor, id, Role.PLATFORM_ADMIN))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void administrativeUseCaseRechecksDatabaseRoleAndRevocation() throws Exception {
        UUID admin = provision("auth0|admin");
        UUID target = provision("auth0|target");
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", admin);
        authenticate("auth0|admin");
        try {
            var actor = actors.currentActor();
            administrativeRoles.assignRole(actor, target, Role.REAL_ESTATE_MANAGER);
            assertThat(jdbc.queryForObject("SELECT role FROM user_roles WHERE user_id = ?", String.class, target))
                    .isEqualTo("REAL_ESTATE_MANAGER");
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", admin);
            assertThatThrownBy(() -> administrativeRoles.assignRole(actor, target, Role.PLATFORM_ADMIN))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void nonJwtAuthenticationCannotResolveCurrentActor() throws Exception {
        mvc.perform(get("/auth/me").with(user("synthetic-user")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void concurrentBootstrapCreatesOnlyOneUser() throws Exception {
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> {
                authenticate("auth0|concurrent");
                ready.countDown();
                try {
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not start");
                    }
                    return bootstrap.execute().user().id();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var ids = new ArrayList<UUID>();
            for (var future : futures) {
                ids.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void technicalEndpointsOtherThanHealthStayClosedOutsideDevelopment() throws Exception {
        for (String path : new String[]{"/v3/api-docs", "/actuator/env", "/actuator/info", "/unmapped"}) {
            mvc.perform(get(path).with(identity("auth0|technical")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        mvc.perform(post("/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void parallelDifferentRolesDoNotOverwriteEachOther() throws Exception {
        UUID id = provision("auth0|parallel-roles");
        try (var executor = Executors.newFixedThreadPool(3)) {
            var tasks = java.util.Arrays.stream(new Role[]{Role.OWNER, Role.TENANT, Role.FIXER})
                    .<Callable<Void>>map(role -> () -> {
                        mvc.perform(post("/auth/select-role").with(identity("auth0|parallel-roles"))
                                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                                .andExpect(status().isOk());
                        return null;
                    }).toList();
            for (var future : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                future.get();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_profiles WHERE user_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void invalidInitialProfileDoesNotPersistPartialAccount() throws Exception {
        mvc.perform(post("/auth/bootstrap").with(jwt().jwt(token -> token.subject("auth0|oversize")
                        .claim("name", "x".repeat(201)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PROFILE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }
}
