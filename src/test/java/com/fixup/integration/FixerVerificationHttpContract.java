package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerReview;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FR-UC-16. Reused unchanged against H2 and PostgreSQL to keep the HTTP/security contract equivalent.
 */
abstract class FixerVerificationHttpContract {
    private static final String ID_CARD = "fixers/id-card.pdf";
    private static final String TRADE = "fixers/trade-certificate.pdf";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired FixerEligibility fixerEligibility;
    @Autowired FixerReview review;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
    }

    // ---------- helpers ----------

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", "fixer@example.test")
                .claim("name", "Synthetic fixer"));
    }

    private UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID provisionFixer(String subject) throws Exception {
        UUID id = provision(subject);
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private UUID provisionAdmin(String subject) throws Exception {
        UUID id = provision(subject);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", id);
        return id;
    }

    private String documents(String... types) {
        var entries = java.util.Arrays.stream(types)
                .map(type -> "{\"type\":\"" + type + "\",\"storageKey\":\"fixers/" + type.toLowerCase() + ".pdf\"}")
                .toList();
        return "{\"documents\":[" + String.join(",", entries) + "]}";
    }

    private org.springframework.test.web.servlet.ResultActions submit(String subject, String body) throws Exception {
        return mvc.perform(post("/fixers/me/verification/documents").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void authenticate(String subject) {
        var jwt = Jwt.withTokenValue("synthetic-context").header("alg", "RS256").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT verification_status FROM fixer_profiles WHERE user_id = ?",
                String.class, id);
    }

    // ---------- autenticación y autorización ----------

    @Test
    void anonymousRequestsToVerificationRoutesReturn401() throws Exception {
        var anyId = UUID.randomUUID();
        for (var request : List.of(get("/fixers/me/verification"),
                post("/fixers/me/verification/documents").contentType(MediaType.APPLICATION_JSON)
                        .content(documents("ID_CARD")),
                post("/fixers/" + anyId + "/verification/approve"),
                post("/fixers/" + anyId + "/verification/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "TENANT"})
    void accountsWithoutTheFixerRoleAreForbidden(String role) throws Exception {
        provision("auth0|not-a-fixer");
        mvc.perform(post("/auth/select-role").with(identity("auth0|not-a-fixer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/fixers/me/verification").with(identity("auth0|not-a-fixer")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        submit("auth0|not-a-fixer", documents("ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents", Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "DISABLED"})
    void inactiveFixersAreForbidden(UserStatus accountStatus) throws Exception {
        UUID id = provisionFixer("auth0|inactive-fixer");
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", accountStatus.name(), id);
        mvc.perform(get("/fixers/me/verification").with(identity("auth0|inactive-fixer")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ---------- consulta del estado propio ----------

    @Test
    void aNewFixerIsPendingWithBothMandatoryDocumentsMissing() throws Exception {
        provisionFixer("auth0|new-fixer");
        mvc.perform(get("/fixers/me/verification").with(identity("auth0|new-fixer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.submittedAt").doesNotExist())
                .andExpect(jsonPath("$.submittedDocuments").isEmpty())
                .andExpect(jsonPath("$.missingDocuments", containsInAnyOrder("ID_CARD", "TRADE_CERTIFICATE")));
    }

    @Test
    void theOwnVerificationNeverExposesTheReviewerOrTheStorageKeys() throws Exception {
        UUID fixer = provisionFixer("auth0|privacy");
        UUID admin = provisionAdmin("auth0|privacy-admin");
        submit("auth0|privacy", "{\"documents\":[{\"type\":\"ID_CARD\",\"storageKey\":\"" + ID_CARD
                + "\"},{\"type\":\"TRADE_CERTIFICATE\",\"storageKey\":\"" + TRADE + "\"}]}")
                .andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/approve").with(identity("auth0|privacy-admin")))
                .andExpect(status().isNoContent());
        var body = mvc.perform(get("/fixers/me/verification").with(identity("auth0|privacy")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(admin.toString()).doesNotContain(ID_CARD).doesNotContain(TRADE)
                .doesNotContain("decidedBy").doesNotContain("storageKey");
    }

    // ---------- entrega de documentos ----------

    @Test
    void documentsMayBeFiledOneAtATimeAndTheReviewOpensWhenTheSetIsComplete() throws Exception {
        UUID id = provisionFixer("auth0|partial");
        submit("auth0|partial", documents("ID_CARD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.submittedAt").doesNotExist())
                .andExpect(jsonPath("$.submittedDocuments", contains("ID_CARD")))
                .andExpect(jsonPath("$.missingDocuments", contains("TRADE_CERTIFICATE")));
        // The partial upload survives: it is not rolled back while the set is incomplete.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents WHERE user_id = ?",
                Integer.class, id)).isEqualTo(1);

        submit("auth0|partial", documents("TRADE_CERTIFICATE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.underReview").value(true))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.missingDocuments").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents WHERE user_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void resubmittingADocumentTypeReplacesItsKeyInsteadOfDuplicatingTheRow() throws Exception {
        UUID id = provisionFixer("auth0|replace");
        submit("auth0|replace", "{\"documents\":[{\"type\":\"ID_CARD\",\"storageKey\":\"fixers/first.pdf\"}]}")
                .andExpect(status().isOk());
        submit("auth0|replace", "{\"documents\":[{\"type\":\"ID_CARD\",\"storageKey\":\"fixers/second.pdf\"}]}")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents WHERE user_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT storage_key FROM fixer_verification_documents WHERE user_id = ? AND document_type = 'ID_CARD'",
                String.class, id)).isEqualTo("fixers/second.pdf");
    }

    @Test
    void optionalDocumentsAloneDoNotOpenTheReview() throws Exception {
        provisionFixer("auth0|optional-only");
        submit("auth0|optional-only", documents("INSURANCE", "BACKGROUND_CHECK")).andExpect(status().isOk())
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.missingDocuments", containsInAnyOrder("ID_CARD", "TRADE_CERTIFICATE")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"documents\":[]}", "{}", "{\"documents\":null}",
            "{\"documents\":[{\"type\":\"PASSPORT\",\"storageKey\":\"k\"}]}",
            "{\"documents\":[{\"type\":\"id_card\",\"storageKey\":\"k\"}]}",
            "{\"documents\":[{\"type\":\"ID_CARD\",\"storageKey\":\"  \"}]}",
            "{\"documents\":[{\"type\":\"ID_CARD\"}]}",
            "{\"documents\":[{\"type\":\"ID_CARD\",\"storageKey\":\"k\",\"userId\":\"00000000-0000-0000-0000-000000000001\"}]}"})
    void malformedSubmissionsReturn400AndStoreNothing(String body) throws Exception {
        provisionFixer("auth0|bad-body");
        submit("auth0|bad-body", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents", Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(value = FixerVerificationStatus.class, names = {"VERIFIED", "SUSPENDED"})
    void verifiedOrSuspendedProfilesCannotSubmitAgain(FixerVerificationStatus blocked) throws Exception {
        UUID id = provisionFixer("auth0|blocked");
        jdbc.update("UPDATE fixer_profiles SET verification_status = ? WHERE user_id = ?", blocked.name(), id);
        submit("auth0|blocked", documents("ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(blocked == FixerVerificationStatus.VERIFIED
                        ? "ALREADY_VERIFIED" : "PROFILE_SUSPENDED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents", Integer.class))
                .isZero();
    }

    // ---------- revisión administrativa ----------

    @Test
    void anAdministratorVerifiesTheFixerAndUnlocksWork() throws Exception {
        UUID fixer = provisionFixer("auth0|to-verify");
        provisionAdmin("auth0|reviewer");
        submit("auth0|to-verify", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        var actor = new CurrentActor(fixer, "auth0|to-verify", Set.of(Role.FIXER), UserStatus.ACTIVE);
        assertThatThrownBy(() -> fixerEligibility.requireVerified(actor))
                .isInstanceOf(FixerNotEligibleException.class);

        mvc.perform(post("/fixers/" + fixer + "/verification/approve").with(identity("auth0|reviewer")))
                .andExpect(status().isNoContent());

        assertThat(statusOf(fixer)).isEqualTo("VERIFIED");
        assertThatCode(() -> fixerEligibility.requireVerified(actor)).doesNotThrowAnyException();
        mvc.perform(get("/fixers/me/verification").with(identity("auth0|to-verify")))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
    }

    @Test
    void aRejectionKeepsTheReasonAndLetsTheFixerTryAgain() throws Exception {
        UUID fixer = provisionFixer("auth0|to-reject");
        provisionAdmin("auth0|reviewer");
        submit("auth0|to-reject", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/reject").with(identity("auth0|reviewer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Certificado ilegible\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/fixers/me/verification").with(identity("auth0|to-reject")))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.rejectionReason").value("Certificado ilegible"));

        submit("auth0|to-reject", documents("ID_CARD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.underReview").value(true))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void aDecisionCannotBeTakenTwice() throws Exception {
        UUID fixer = provisionFixer("auth0|decided-once");
        provisionAdmin("auth0|reviewer");
        submit("auth0|decided-once", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/approve").with(identity("auth0|reviewer")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/fixers/" + fixer + "/verification/reject").with(identity("auth0|reviewer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"tarde\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_UNDER_REVIEW"));
        assertThat(statusOf(fixer)).isEqualTo("VERIFIED");
    }

    @Test
    void aProfileWithoutSubmissionCannotBeDecided() throws Exception {
        UUID fixer = provisionFixer("auth0|no-submission");
        provisionAdmin("auth0|reviewer");
        mvc.perform(post("/fixers/" + fixer + "/verification/approve").with(identity("auth0|reviewer")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_UNDER_REVIEW"));
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    @Test
    void decidingAnUnknownProfileIsAConflictAndNotAnInternalError() throws Exception {
        provisionAdmin("auth0|reviewer");
        mvc.perform(post("/fixers/" + UUID.randomUUID() + "/verification/approve")
                .with(identity("auth0|reviewer")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    void anAdministratorCannotDecideTheirOwnVerification() throws Exception {
        UUID admin = provisionFixer("auth0|admin-fixer");
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", admin);
        submit("auth0|admin-fixer", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        mvc.perform(post("/fixers/" + admin + "/verification/approve").with(identity("auth0|admin-fixer")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_REVIEW"));
        assertThat(statusOf(admin)).isEqualTo("PENDING");
    }

    @Test
    void anOrdinaryAccountCannotDecideAVerification() throws Exception {
        UUID fixer = provisionFixer("auth0|victim-fixer");
        provision("auth0|intruder");
        submit("auth0|victim-fixer", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/approve").with(identity("auth0|intruder")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"reason\":null}"})
    void aRejectionAlwaysNeedsAReason(String body) throws Exception {
        UUID fixer = provisionFixer("auth0|reason-needed");
        provisionAdmin("auth0|reviewer");
        submit("auth0|reason-needed", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/reject").with(identity("auth0|reviewer"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    @Test
    void aForgedAdminAuthorityInTheTokenDoesNotAuthorizeADecision() throws Exception {
        UUID fixer = provisionFixer("auth0|forged-target");
        provision("auth0|forged-admin");
        submit("auth0|forged-target", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/approve")
                .with(jwt().jwt(token -> token.subject("auth0|forged-admin")
                        .claim("roles", List.of("PLATFORM_ADMIN")))
                        .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    @Test
    void revokingTheAdministrativeRoleStopsFurtherDecisions() throws Exception {
        UUID first = provisionFixer("auth0|first-fixer");
        UUID second = provisionFixer("auth0|second-fixer");
        UUID admin = provisionAdmin("auth0|revocable");
        submit("auth0|first-fixer", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        submit("auth0|second-fixer", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        mvc.perform(post("/fixers/" + first + "/verification/approve").with(identity("auth0|revocable")))
                .andExpect(status().isNoContent());
        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND role = 'PLATFORM_ADMIN'", admin);
        mvc.perform(post("/fixers/" + second + "/verification/approve").with(identity("auth0|revocable")))
                .andExpect(status().isForbidden());
        assertThat(statusOf(second)).isEqualTo("PENDING");
    }

    @Test
    void theUseCaseRechecksPrivilegesEvenWhenCalledOutsideHttp() throws Exception {
        UUID fixer = provisionFixer("auth0|direct-call");
        UUID intruder = provision("auth0|direct-intruder");
        submit("auth0|direct-call", documents("ID_CARD", "TRADE_CERTIFICATE")).andExpect(status().isOk());
        // A CurrentActor built by the caller claiming PLATFORM_ADMIN never replaces the stored roles.
        var forged = new CurrentActor(intruder, "auth0|direct-intruder", Set.of(Role.PLATFORM_ADMIN),
                UserStatus.ACTIVE);
        authenticate("auth0|direct-intruder");
        try {
            assertThatThrownBy(() -> review.approve(forged, fixer)).isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    @Test
    void verificationRoutesAreRegisteredAndCorsMatchesTheIdentityContract() throws Exception {
        mvc.perform(options("/fixers/me/verification/documents").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        mvc.perform(options("/fixers/me/verification").header("Origin", "https://untrusted.example.test")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
