package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-UC-25: proves that repair_requests and quotations are isolated at the database level, not only
 * by application logic. Every assertion drives the exact session variables and the fixup_app role
 * that RlsSessionTransactionManager activates for a real request (see V6__row_level_security.sql)
 * and then reads or writes straight through plain SQL, so a bug in the Java-side checks
 * (RequestAccess, QuotationAccess, GetRepairRequest...) could never make this test pass by accident.
 *
 * <p>Requires a real PostgreSQL: H2 has no Row-Level Security, so this cannot run under the "test"
 * profile's default H2 datasource, only against the Testcontainers instance below.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
class PostgresRowLevelSecurityIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
    }

    // ---------- fixture helpers: real bootstrap/role-selection HTTP flow, like the other IT tests ----------

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }

    private UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID provisionWithRole(String subject, String role) throws Exception {
        UUID id = provision(subject);
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private UUID provisionAdmin(String subject) throws Exception {
        UUID id = provision(subject);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", id);
        return id;
    }

    private UUID provisionVerifiedFixer(String subject, String specialty) throws Exception {
        UUID fixerId = provisionWithRole(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", fixerId);
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, ?)", fixerId, specialty);
        return fixerId;
    }

    // Fixtures are written directly with SQL, under the unrestricted connection: this is data setup,
    // not the thing under test. RLS itself is only exercised through actingAs() below.
    private UUID createRequest(UUID ownerId, String specialty, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO repair_requests (id, owner_user_id, specialty, title, description, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'fixture description', 'OPEN', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, ownerId, specialty, title);
        return id;
    }

    private UUID createQuotation(UUID requestId, UUID fixerId, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO quotations (id, request_id, fixer_user_id, amount, estimated_days, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'SUBMITTED', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP)",
                id, requestId, fixerId, amount);
        return id;
    }

    // FR-UC-23: a fixer's identity verification document, fixture-inserted the same way the others
    // are -- a real media_assets row (media_id is a real FK now, not a free-text storage key) plus
    // the fixer_verification_documents row that files it under a document type.
    private UUID createVerificationDocument(UUID fixerUserId, String documentType) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("INSERT INTO media_assets (id, owner_user_id, purpose, object_key, content_type, size_bytes, "
                        + "status, upload_expires_at, confirmed_at, created_at) VALUES (?, ?, 'FIXER_VERIFICATION', "
                        + "?, 'image/jpeg', 100, 'READY', CURRENT_TIMESTAMP + INTERVAL '1' DAY, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP)",
                mediaId, fixerUserId, "verification/" + fixerUserId + "/" + mediaId + ".jpg");
        jdbc.update("INSERT INTO fixer_verification_documents (user_id, document_type, media_id, submitted_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                fixerUserId, documentType, mediaId);
        return mediaId;
    }

    private String requestTitle(UUID requestId) {
        return jdbc.queryForObject("SELECT title FROM repair_requests WHERE id = ?", String.class, requestId);
    }

    private long quotationAmount(UUID quotationId) {
        return jdbc.queryForObject("SELECT amount FROM quotations WHERE id = ?", Long.class, quotationId);
    }

    // ---------- RLS session helper: exactly what RlsSessionTransactionManager does for a request ----------

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    /** Runs work on a single connection acting as {@code userId}/{@code role}, then commits. */
    private <T> T actingAs(UUID userId, String role, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE fixup_app");
            }
            try (var statement = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true), "
                    + "set_config('app.current_user_roles', ?, true)")) {
                statement.setString(1, userId.toString());
                statement.setString(2, role);
                statement.execute();
            }
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<UUID> queryUuids(Connection connection, String sql) throws SQLException {
        var ids = new java.util.ArrayList<UUID>();
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                ids.add((UUID) rs.getObject(1));
            }
        }
        return ids;
    }

    private List<UUID> visibleRequestIds(UUID userId, String role) {
        return actingAs(userId, role, connection -> queryUuids(connection, "SELECT id FROM repair_requests"));
    }

    private List<UUID> visibleQuotationIds(UUID userId, String role) {
        return actingAs(userId, role, connection -> queryUuids(connection, "SELECT id FROM quotations"));
    }

    private List<UUID> visibleVerificationDocumentMediaIds(UUID userId, String role) {
        return actingAs(userId, role,
                connection -> queryUuids(connection, "SELECT media_id FROM fixer_verification_documents"));
    }

    private int attemptUpdateRequestTitle(UUID userId, String role, UUID requestId, String newTitle) {
        return actingAs(userId, role, connection -> {
            try (var statement = connection.prepareStatement("UPDATE repair_requests SET title = ? WHERE id = ?")) {
                statement.setString(1, newTitle);
                statement.setObject(2, requestId);
                return statement.executeUpdate();
            }
        });
    }

    private int attemptUpdateQuotationAmount(UUID userId, String role, UUID quotationId, long newAmount) {
        return actingAs(userId, role, connection -> {
            try (var statement = connection.prepareStatement("UPDATE quotations SET amount = ? WHERE id = ?")) {
                statement.setLong(1, newAmount);
                statement.setObject(2, quotationId);
                return statement.executeUpdate();
            }
        });
    }

    // ---------- Tests ----------

    @Test
    void ownerAndTenantOnlySeeTheirOwnRequests() throws Exception {
        UUID ownerA = provisionWithRole("auth0|rls-owner-a", "OWNER");
        UUID tenantB = provisionWithRole("auth0|rls-tenant-b", "TENANT");
        UUID requestA = createRequest(ownerA, "PLUMBING", "Fuga de owner A");
        UUID requestB = createRequest(tenantB, "ELECTRICAL", "Corto de tenant B");

        assertThat(visibleRequestIds(ownerA, "OWNER")).contains(requestA).doesNotContain(requestB);
        assertThat(visibleRequestIds(tenantB, "TENANT")).contains(requestB).doesNotContain(requestA);
    }

    @Test
    void ownerCannotModifyAnotherOwnersRequest() throws Exception {
        UUID ownerA = provisionWithRole("auth0|rls-owner-upd-a", "OWNER");
        UUID ownerB = provisionWithRole("auth0|rls-owner-upd-b", "OWNER");
        UUID requestB = createRequest(ownerB, "PAINTING", "Original de B");

        int affected = attemptUpdateRequestTitle(ownerA, "OWNER", requestB, "hackeado por A");

        assertThat(affected).isZero();
        assertThat(requestTitle(requestB)).isEqualTo("Original de B");
    }

    @Test
    void fixerOnlySeesTheirOwnQuotationsAndTheOwnerSeesOnlyTheirRequestOffers() throws Exception {
        UUID ownerA = provisionWithRole("auth0|rls-owner-q-a", "OWNER");
        UUID ownerB = provisionWithRole("auth0|rls-owner-q-b", "OWNER");
        UUID fixerA = provisionVerifiedFixer("auth0|rls-fixer-q-a", "PLUMBING");
        UUID fixerB = provisionVerifiedFixer("auth0|rls-fixer-q-b", "ELECTRICAL");

        UUID requestA = createRequest(ownerA, "PLUMBING", "Solicitud de A");
        UUID requestB = createRequest(ownerB, "ELECTRICAL", "Solicitud de B");
        UUID quotationA = createQuotation(requestA, fixerA, 100_000L);
        UUID quotationB = createQuotation(requestB, fixerB, 200_000L);

        assertThat(visibleQuotationIds(fixerA, "FIXER")).containsExactly(quotationA);
        assertThat(visibleQuotationIds(fixerB, "FIXER")).containsExactly(quotationB);

        // The owner sees the offers on their own request, never the ones on somebody else's.
        assertThat(visibleQuotationIds(ownerA, "OWNER")).contains(quotationA).doesNotContain(quotationB);
        assertThat(visibleQuotationIds(ownerB, "OWNER")).contains(quotationB).doesNotContain(quotationA);
    }

    @Test
    void fixerCannotModifyAnotherFixersQuotation() throws Exception {
        UUID owner = provisionWithRole("auth0|rls-owner-fq", "OWNER");
        UUID fixerA = provisionVerifiedFixer("auth0|rls-fixer-mod-a", "PLUMBING");
        UUID fixerB = provisionVerifiedFixer("auth0|rls-fixer-mod-b", "PLUMBING");
        UUID request = createRequest(owner, "PLUMBING", "Solicitud compartida");
        UUID quotationA = createQuotation(request, fixerA, 150_000L);

        int affected = attemptUpdateQuotationAmount(fixerB, "FIXER", quotationA, 1L);

        assertThat(affected).isZero();
        assertThat(quotationAmount(quotationA)).isEqualTo(150_000L);
    }

    @Test
    void fixerSeesTheOpenMarketplaceRequestButCannotModifyIt() throws Exception {
        UUID owner = provisionWithRole("auth0|rls-owner-market", "OWNER");
        UUID fixer = provisionVerifiedFixer("auth0|rls-fixer-market", "PLUMBING");
        UUID request = createRequest(owner, "PLUMBING", "Oferta abierta");

        // FR-UC-18: any active fixer must see open requests in order to quote them (and SubmitQuotation
        // takes a row lock while doing so), so the UPDATE policy's USING clause must admit this row too
        // -- PostgreSQL requires SELECT ... FOR UPDATE to satisfy both the SELECT and UPDATE policies.
        assertThat(visibleRequestIds(fixer, "FIXER")).contains(request);

        // ...but that lockable-for-browsing row is not a writable one: WITH CHECK stays limited to the
        // owner/assigned fixer/admin, so PostgreSQL rejects the write outright as a policy violation,
        // rather than silently updating zero rows.
        assertThatThrownBy(() -> attemptUpdateRequestTitle(fixer, "FIXER", request, "hackeado por fixer"))
                .hasMessageContaining("row-level security policy");
        assertThat(requestTitle(request)).isEqualTo("Oferta abierta");
    }

    @Test
    void platformAdminHasBroadReadAndWriteAccessAcrossAccounts() throws Exception {
        UUID adminX = provisionAdmin("auth0|rls-admin-x");
        UUID adminY = provisionAdmin("auth0|rls-admin-y");
        UUID ownerA = provisionWithRole("auth0|rls-owner-admin-a", "OWNER");
        UUID ownerB = provisionWithRole("auth0|rls-owner-admin-b", "OWNER");
        UUID requestA = createRequest(ownerA, "PLUMBING", "De A");
        UUID requestB = createRequest(ownerB, "MASONRY", "De B");

        assertThat(visibleRequestIds(adminX, "PLATFORM_ADMIN")).contains(requestA, requestB);

        int affected = attemptUpdateRequestTitle(adminY, "PLATFORM_ADMIN", requestB, "corregido por admin");
        assertThat(affected).isEqualTo(1);
        assertThat(requestTitle(requestB)).isEqualTo("corregido por admin");
    }

    @Test
    void unrelatedStrangerSeesNothing() throws Exception {
        UUID owner = provisionWithRole("auth0|rls-owner-stranger", "OWNER");
        UUID stranger = provision("auth0|rls-stranger");
        UUID request = createRequest(owner, "CARPENTRY", "Privado de owner");

        // A plain authenticated account with no role at all: not owner, not fixer, not admin.
        assertThat(visibleRequestIds(stranger, "")).doesNotContain(request);
    }

    // ---------- FR-UC-23: identity verification documents ----------

    @Test
    void fixerCannotSeeAnotherFixersVerificationDocuments() throws Exception {
        UUID fixerA = provisionWithRole("auth0|rls-vdoc-fixer-a", "FIXER");
        UUID fixerB = provisionWithRole("auth0|rls-vdoc-fixer-b", "FIXER");
        UUID documentA = createVerificationDocument(fixerA, "ID_CARD");
        UUID documentB = createVerificationDocument(fixerB, "ID_CARD");

        assertThat(visibleVerificationDocumentMediaIds(fixerA, "FIXER"))
                .containsExactly(documentA);
        assertThat(visibleVerificationDocumentMediaIds(fixerB, "FIXER"))
                .containsExactly(documentB);
    }

    @Test
    void platformAdminSeesAnyFixersVerificationDocuments() throws Exception {
        UUID admin = provisionAdmin("auth0|rls-vdoc-admin");
        UUID fixerA = provisionWithRole("auth0|rls-vdoc-fixer-admin-a", "FIXER");
        UUID fixerB = provisionWithRole("auth0|rls-vdoc-fixer-admin-b", "FIXER");
        UUID documentA = createVerificationDocument(fixerA, "ID_CARD");
        UUID documentB = createVerificationDocument(fixerB, "TRADE_CERTIFICATE");

        assertThat(visibleVerificationDocumentMediaIds(admin, "PLATFORM_ADMIN"))
                .contains(documentA, documentB);
    }
}
