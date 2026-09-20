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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FR-UC-23. Reused unchanged against H2 and PostgreSQL to keep the HTTP/security contract equivalent.
 */
abstract class FixerVerificationHttpContract {

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
        TestStorageConfiguration.instance().clear();
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

    /** Uploads and confirms a real FIXER_VERIFICATION media asset, exactly like a real client would. */
    private UUID uploadVerificationDocument(String subject) throws Exception {
        String body = "{\"purpose\":\"FIXER_VERIFICATION\",\"contentType\":\"image/jpeg\",\"sizeBytes\":"
                + TestStorageConfiguration.JPEG_MAGIC.length + "}";
        var uploadRes = mvc.perform(post("/media/uploads").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        UUID mediaId = UUID.fromString(mapper.readTree(uploadRes.getResponse().getContentAsString())
                .get("mediaId").asText());
        String objectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class,
                mediaId);

        TestStorageConfiguration.instance().put(objectKey, TestStorageConfiguration.JPEG_MAGIC, "image/jpeg");

        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        return mediaId;
    }

    /** Uploads one real, confirmed document per type and builds the submission body around them. */
    private String documents(String subject, String... types) throws Exception {
        var entries = new java.util.ArrayList<String>();
        for (String type : types) {
            UUID mediaId = uploadVerificationDocument(subject);
            entries.add("{\"type\":\"" + type + "\",\"mediaId\":\"" + mediaId + "\"}");
        }
        return "{\"consentVersion\":\"v1\",\"documents\":[" + String.join(",", entries) + "]}";
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
                        .content("{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\""
                                + UUID.randomUUID() + "\"}]}"),
                get("/fixers/" + anyId + "/verification"),
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
        submit("auth0|not-a-fixer", "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\""
                + UUID.randomUUID() + "\"},{\"type\":\"TRADE_CERTIFICATE\",\"mediaId\":\""
                + UUID.randomUUID() + "\"}]}")
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
    void theOwnVerificationNeverExposesTheReviewerOrDocumentReferences() throws Exception {
        UUID fixer = provisionFixer("auth0|privacy");
        UUID admin = provisionAdmin("auth0|privacy-admin");
        submit("auth0|privacy", documents("auth0|privacy", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/approve").with(identity("auth0|privacy-admin")))
                .andExpect(status().isNoContent());
        var body = mvc.perform(get("/fixers/me/verification").with(identity("auth0|privacy")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(admin.toString())
                .doesNotContain("decidedBy").doesNotContain("mediaId").doesNotContain("readUrl");
    }

    // ---------- entrega de documentos ----------

    @Test
    void documentsMayBeFiledOneAtATimeAndTheReviewOpensWhenTheSetIsComplete() throws Exception {
        UUID id = provisionFixer("auth0|partial");
        submit("auth0|partial", documents("auth0|partial", "ID_CARD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.submittedAt").doesNotExist())
                .andExpect(jsonPath("$.submittedDocuments", contains("ID_CARD")))
                .andExpect(jsonPath("$.missingDocuments", contains("TRADE_CERTIFICATE")));
        // The partial upload survives: it is not rolled back while the set is incomplete.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents WHERE user_id = ?",
                Integer.class, id)).isEqualTo(1);

        submit("auth0|partial", documents("auth0|partial", "TRADE_CERTIFICATE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.underReview").value(true))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.missingDocuments").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents WHERE user_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void resubmittingADocumentTypeReplacesItsMediaInsteadOfDuplicatingTheRow() throws Exception {
        UUID id = provisionFixer("auth0|replace");
        UUID first = uploadVerificationDocument("auth0|replace");
        submit("auth0|replace", "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"" + first + "\"}]}")
                .andExpect(status().isOk());
        UUID second = uploadVerificationDocument("auth0|replace");
        submit("auth0|replace", "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"" + second + "\"}]}")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents WHERE user_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT media_id FROM fixer_verification_documents WHERE user_id = ? AND document_type = 'ID_CARD'",
                UUID.class, id)).isEqualTo(second);
    }

    @Test
    void optionalDocumentsAloneDoNotOpenTheReview() throws Exception {
        provisionFixer("auth0|optional-only");
        submit("auth0|optional-only", documents("auth0|optional-only", "INSURANCE", "BACKGROUND_CHECK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.missingDocuments", containsInAnyOrder("ID_CARD", "TRADE_CERTIFICATE")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"documents\":[]}", "{}", "{\"documents\":null}",
            "{\"documents\":[{\"type\":\"PASSPORT\",\"mediaId\":\"00000000-0000-0000-0000-000000000099\"}]}",
            "{\"documents\":[{\"type\":\"id_card\",\"mediaId\":\"00000000-0000-0000-0000-000000000099\"}]}",
            "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"not-a-uuid\"}]}",
            "{\"documents\":[{\"type\":\"ID_CARD\"}]}",
            "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"00000000-0000-0000-0000-000000000099\","
                    + "\"userId\":\"00000000-0000-0000-0000-000000000001\"}]}"})
    void malformedSubmissionsReturn400AndStoreNothing(String body) throws Exception {
        provisionFixer("auth0|bad-body");
        submit("auth0|bad-body", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents", Integer.class))
                .isZero();
    }

    @Test
    void submittingAFakeOrUnauthorizedMediaIdIsRejectedAndNeverOpensReview() throws Exception {
        provisionFixer("auth0|fake-media");

        // A mediaId that does not exist at all.
        submit("auth0|fake-media", "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\""
                + UUID.randomUUID() + "\"}]}")
                .andExpect(status().isNotFound());

        // A mediaId that exists but belongs to a different fixer.
        provisionFixer("auth0|fake-media-victim");
        UUID strangersMedia = uploadVerificationDocument("auth0|fake-media-victim");
        submit("auth0|fake-media", "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"" + strangersMedia + "\"}]}")
                .andExpect(status().isNotFound());

        mvc.perform(get("/fixers/me/verification").with(identity("auth0|fake-media")))
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.submittedDocuments").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents", Integer.class))
                .isZero();
    }

    @Test
    void submittingAMediaAssetWithTheWrongPurposeIsRejected() throws Exception {
        String subject = "auth0|wrong-purpose";
        provisionFixer(subject);
        String body = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":"
                + TestStorageConfiguration.JPEG_MAGIC.length + "}";
        // FIXER_PORTFOLIO requires an already-verified fixer, which this one is not yet -> use an
        // admin-inserted portfolio-purpose asset directly to isolate the purpose check itself.
        UUID mediaId = UUID.randomUUID();
        UUID ownerId = jdbc.queryForObject("SELECT id FROM users WHERE auth0_subject = ?", UUID.class, subject);
        String objectKey = "portfolio/" + ownerId + "/" + mediaId + ".jpg";
        TestStorageConfiguration.instance().put(objectKey, TestStorageConfiguration.JPEG_MAGIC, "image/jpeg");
        jdbc.update("INSERT INTO media_assets (id, owner_user_id, purpose, object_key, content_type, size_bytes, "
                        + "status, upload_expires_at, confirmed_at, created_at) VALUES (?, ?, 'FIXER_PORTFOLIO', ?, "
                        + "'image/jpeg', 100, 'READY', CURRENT_TIMESTAMP + INTERVAL '1' DAY, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP)",
                mediaId, ownerId, objectKey);

        submit(subject, "{\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"" + mediaId + "\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PURPOSE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fixer_verification_documents", Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(value = FixerVerificationStatus.class, names = {"VERIFIED", "SUSPENDED"})
    void verifiedOrSuspendedProfilesCannotSubmitAgain(FixerVerificationStatus blocked) throws Exception {
        UUID id = provisionFixer("auth0|blocked");
        jdbc.update("UPDATE fixer_profiles SET verification_status = ? WHERE user_id = ?", blocked.name(), id);
        submit("auth0|blocked", documents("auth0|blocked", "ID_CARD", "TRADE_CERTIFICATE"))
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
        submit("auth0|to-verify", documents("auth0|to-verify", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
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
        UUID idCard = uploadVerificationDocument("auth0|to-reject");
        submit("auth0|to-reject", "{\"consentVersion\":\"v1\",\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"" + idCard
                + "\"},{\"type\":\"TRADE_CERTIFICATE\",\"mediaId\":\"" + uploadVerificationDocument("auth0|to-reject")
                + "\"}]}").andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/reject").with(identity("auth0|reviewer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Certificado ilegible\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/fixers/me/verification").with(identity("auth0|to-reject")))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.underReview").value(false))
                .andExpect(jsonPath("$.rejectionReason").value("Certificado ilegible"));

        // Resubmitting the same, already-filed ID_CARD media is a no-op re-validation-wise; the set
        // is still complete from before, so the review opens again straight away.
        submit("auth0|to-reject", "{\"consentVersion\":\"v1\",\"documents\":[{\"type\":\"ID_CARD\",\"mediaId\":\"" + idCard + "\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.underReview").value(true))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void aDecisionCannotBeTakenTwice() throws Exception {
        UUID fixer = provisionFixer("auth0|decided-once");
        provisionAdmin("auth0|reviewer");
        submit("auth0|decided-once", documents("auth0|decided-once", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
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
        submit("auth0|admin-fixer", documents("auth0|admin-fixer", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
        mvc.perform(post("/fixers/" + admin + "/verification/approve").with(identity("auth0|admin-fixer")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_REVIEW"));
        assertThat(statusOf(admin)).isEqualTo("PENDING");
    }

    @Test
    void anOrdinaryAccountCannotDecideAVerification() throws Exception {
        UUID fixer = provisionFixer("auth0|victim-fixer");
        provision("auth0|intruder");
        submit("auth0|victim-fixer", documents("auth0|victim-fixer", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/approve").with(identity("auth0|intruder")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"reason\":null}"})
    void aRejectionAlwaysNeedsAReason(String body) throws Exception {
        UUID fixer = provisionFixer("auth0|reason-needed");
        provisionAdmin("auth0|reviewer");
        submit("auth0|reason-needed", documents("auth0|reason-needed", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
        mvc.perform(post("/fixers/" + fixer + "/verification/reject").with(identity("auth0|reviewer"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    @Test
    void aForgedAdminAuthorityInTheTokenDoesNotAuthorizeADecision() throws Exception {
        UUID fixer = provisionFixer("auth0|forged-target");
        provision("auth0|forged-admin");
        submit("auth0|forged-target", documents("auth0|forged-target", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
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
        submit("auth0|first-fixer", documents("auth0|first-fixer", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
        submit("auth0|second-fixer", documents("auth0|second-fixer", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
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
        submit("auth0|direct-call", documents("auth0|direct-call", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());
        // A CurrentActor built by the caller claiming PLATFORM_ADMIN never replaces the stored roles.
        var forged = new CurrentActor(intruder, "auth0|direct-intruder", Set.of(Role.PLATFORM_ADMIN),
                UserStatus.ACTIVE);
        authenticate("auth0|direct-intruder");
        try {
            assertThatThrownBy(() -> review.approve(forged, fixer)).isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
            // isCurrentAdmin() resolved the intruder's real CurrentActor to check the claim, which
            // left it set on this thread: nothing clears DatabaseActorContext outside of a real HTTP
            // request, and this call never made one.
            com.fixup.shared.security.DatabaseActorContext.clear();
        }
        assertThat(statusOf(fixer)).isEqualTo("PENDING");
    }

    // ---------- revisión: consulta administrativa de documentos ----------

    @Test
    void anAdministratorCanViewTheFixersRegisteredDocumentsBeforeDeciding() throws Exception {
        UUID fixer = provisionFixer("auth0|to-review");
        provisionAdmin("auth0|doc-reviewer");
        submit("auth0|to-review", documents("auth0|to-review", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());

        var result = mvc.perform(get("/fixers/" + fixer + "/verification").with(identity("auth0|doc-reviewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixerUserId").value(fixer.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.underReview").value(true))
                .andExpect(jsonPath("$.documents", hasSize(2)))
                .andExpect(jsonPath("$.documents[0].readUrl").isNotEmpty())
                .andExpect(jsonPath("$.documents[0].mediaId").isNotEmpty())
                .andReturn();

        // The admin's read access to someone's identity document is short-lived, not standing: the
        // signed URL must expire in minutes, not hours, so a leaked link or an idle review tab stops
        // granting access on its own.
        var before = Instant.now();
        var documents = mapper.readTree(result.getResponse().getContentAsString()).get("documents");
        for (var document : documents) {
            Instant expiresAt = Instant.parse(document.get("readUrlExpiresAt").asText());
            assertThat(expiresAt).isAfter(before)
                    .isBeforeOrEqualTo(before.plus(java.time.Duration.ofMinutes(16)));
        }
    }

    @Test
    void nonAdminCannotViewAnotherFixersVerificationForReview() throws Exception {
        UUID fixer = provisionFixer("auth0|private-fixer");
        provision("auth0|nosy-intruder");
        submit("auth0|private-fixer", documents("auth0|private-fixer", "ID_CARD", "TRADE_CERTIFICATE"))
                .andExpect(status().isOk());

        mvc.perform(get("/fixers/" + fixer + "/verification").with(identity("auth0|nosy-intruder")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
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

    @Test
    void fixerSavesAndRetrievesSpecialties() throws Exception {
        String subject = "auth0|fixer-specialties-crud";
        provisionFixer(subject);

        var updateBody = "{\"specialties\":[\"PLUMBING\",\"ELECTRICAL\"]}";
        mvc.perform(post("/fixers/me/specialties").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialties", containsInAnyOrder("PLUMBING", "ELECTRICAL")));

        mvc.perform(get("/fixers/me/verification").with(identity(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialties", containsInAnyOrder("PLUMBING", "ELECTRICAL")));
    }

    @Test
    void suspendedFixerCannotUpdateSpecialties() throws Exception {
        String subject = "auth0|fixer-suspended";
        UUID fixerId = provisionFixer(subject);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", fixerId);

        var updateBody = "{\"specialties\":[\"PLUMBING\"]}";
        mvc.perform(post("/fixers/me/specialties").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void specialtiesRequestWithAdditionalFieldsIsRejected() throws Exception {
        String subject = "auth0|fixer-extra-fields";
        provisionFixer(subject);

        var updateBody = "{\"specialties\":[\"PLUMBING\"],\"extraField\":\"malicious\"}";
        mvc.perform(post("/fixers/me/specialties").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void specialtiesRequestWithEmptyListIsRejected() throws Exception {
        String subject = "auth0|fixer-empty-spec";
        provisionFixer(subject);

        var updateBody = "{\"specialties\":[]}";
        mvc.perform(post("/fixers/me/specialties").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void specialtiesRequestWithDuplicatesOrNullIsRejected() throws Exception {
        String subject = "auth0|fixer-dup-spec";
        provisionFixer(subject);

        var dupBody = "{\"specialties\":[\"PLUMBING\",\"PLUMBING\"]}";
        mvc.perform(post("/fixers/me/specialties").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(dupBody))
                .andExpect(status().isBadRequest());

        var nullBody = "{\"specialties\":[\"PLUMBING\",null]}";
        mvc.perform(post("/fixers/me/specialties").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(nullBody))
                .andExpect(status().isBadRequest());
    }
}
