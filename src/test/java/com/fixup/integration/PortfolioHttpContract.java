package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.media.domain.PortfolioPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fixup.media.domain.FixerPortfolios;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class PortfolioHttpContract {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;

    @Autowired protected FixerPortfolios portfolios;
    @Autowired protected com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    @AfterEach
    void resetDatabase() {
        SecurityContextHolder.clearContext();
        TestStorageConfiguration.instance().clear();
        databaseCleaner.clean();
    }

    protected RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic Fixer"));
    }

    protected UUID verifiedFixer(String subject) throws Exception {
        var created = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        var id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", id);
        return id;
    }

    private UUID uploadAndConfirm(String subject, byte[] content, String declaredMime) throws Exception {
        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"" + declaredMime + "\",\"sizeBytes\":" + content.length + "}";
        var uploadRes = mvc.perform(post("/media/uploads").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn();

        var mediaId = UUID.fromString(mapper.readTree(uploadRes.getResponse().getContentAsString()).get("mediaId").asText());
        var objectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class, mediaId);

        TestStorageConfiguration.instance().put(objectKey, content, declaredMime);

        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        return mediaId;
    }

    private UUID uploadAndConfirmJpeg(String subject) throws Exception {
        return uploadAndConfirm(subject, TestStorageConfiguration.JPEG_MAGIC, "image/jpeg");
    }

    private ResultActions publishPieceWithMedia(String subject, UUID mediaId, String title) throws Exception {
        var req = "{\"mediaId\":\"" + mediaId + "\",\"title\":\"" + title + "\",\"description\":\"Trabajo terminado\"}";
        return mvc.perform(post("/media/me/portfolio/pieces").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(req));
    }

    private ResultActions publish(String subject, String title) throws Exception {
        var mediaId = uploadAndConfirmJpeg(subject);
        return publishPieceWithMedia(subject, mediaId, title);
    }

    private UUID publishAndReadId(String subject, String title) throws Exception {
        var result = publish(subject, title).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void anonymousPortfolioRequestsReturnUniform401() throws Exception {
        for (var request : List.of(
                get("/media/me/portfolio"),
                post("/media/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":10}"),
                post("/media/uploads/" + UUID.randomUUID() + "/confirm"),
                post("/media/me/portfolio/pieces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaId\":\"" + UUID.randomUUID() + "\",\"title\":\"t\"}"),
                post("/media/me/portfolio/publish"),
                post("/media/me/portfolio/unpublish"),
                delete("/media/me/portfolio/pieces/" + UUID.randomUUID()),
                post("/media/me/portfolio/pieces/" + UUID.randomUUID() + "/hide"),
                post("/media/me/portfolio/pieces/" + UUID.randomUUID() + "/show"),
                get("/media/fixers/" + UUID.randomUUID() + "/portfolio"))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void deprecatedRoutesWithoutPiecesDoNotExist() throws Exception {
        verifiedFixer("auth0|route-checker");
        mvc.perform(post("/media/me/portfolio").with(identity("auth0|route-checker"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"" + UUID.randomUUID() + "\",\"title\":\"t\"}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/media/me/portfolio/" + UUID.randomUUID()).with(identity("auth0|route-checker")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/media/me/portfolio/" + UUID.randomUUID() + "/hide").with(identity("auth0|route-checker")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/media/me/portfolio/" + UUID.randomUUID() + "/show").with(identity("auth0|route-checker")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aVerifiedFixerPublishesAPieceThatStartsPublic() throws Exception {
        verifiedFixer("auth0|verified-fixer");

        publish("auth0|verified-fixer", "Cocina").andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Cocina"))
                .andExpect(jsonPath("$.mediaId").isNotEmpty())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.readUrl").isNotEmpty())
                .andExpect(jsonPath("$.readUrlExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.storageKey").doesNotExist());
    }

    @Test
    void piecesAreAppendedSoTheGalleryKeepsPublicationOrder() throws Exception {
        verifiedFixer("auth0|ordered");

        publish("auth0|ordered", "Primero").andExpect(status().isCreated());
        publish("auth0|ordered", "Segundo").andExpect(status().isCreated());
        publish("auth0|ordered", "Tercero").andExpect(status().isCreated());

        mvc.perform(get("/media/me/portfolio").with(identity("auth0|ordered")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.pieces.length()").value(3))
                .andExpect(jsonPath("$.pieces[0].position").value(1))
                .andExpect(jsonPath("$.pieces[0].title").value("Primero"))
                .andExpect(jsonPath("$.pieces[1].position").value(2))
                .andExpect(jsonPath("$.pieces[1].title").value("Segundo"))
                .andExpect(jsonPath("$.pieces[2].position").value(3))
                .andExpect(jsonPath("$.pieces[2].title").value("Tercero"));
    }

    @Test
    void anUnverifiedFixerCannotUploadMedia() throws Exception {
        mvc.perform(post("/auth/bootstrap").with(identity("auth0|pending-fixer")))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/select-role").with(identity("auth0|pending-fixer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                .andExpect(status().isOk());

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":100}";
        mvc.perform(post("/media/uploads").with(identity("auth0|pending-fixer"))
                .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void anOwnerCannotUploadFixerPortfolioMedia() throws Exception {
        mvc.perform(post("/auth/bootstrap").with(identity("auth0|owner"))).andExpect(status().isCreated());
        mvc.perform(post("/auth/select-role").with(identity("auth0|owner"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}")).andExpect(status().isOk());

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":100}";
        mvc.perform(post("/media/uploads").with(identity("auth0|owner"))
                .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void aSuspendedUserCannotUploadMedia() throws Exception {
        var id = verifiedFixer("auth0|suspended");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", id);

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":100}";
        mvc.perform(post("/media/uploads").with(identity("auth0|suspended"))
                .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void portfolioCannotBePublishedWithFewerThanThreePublicPhotos() throws Exception {
        verifiedFixer("auth0|few-photos");

        publish("auth0|few-photos", "P1").andExpect(status().isCreated());
        publish("auth0|few-photos", "P2").andExpect(status().isCreated());

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|few-photos")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_INSUFFICIENT_PIECES"));
    }

    @Test
    void portfolioPublishesWithThreePhotosAndIsIdempotent() throws Exception {
        verifiedFixer("auth0|three-photos");

        publish("auth0|three-photos", "P1").andExpect(status().isCreated());
        publish("auth0|three-photos", "P2").andExpect(status().isCreated());
        publish("auth0|three-photos", "P3").andExpect(status().isCreated());

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|three-photos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());

        // Repeated publication is idempotent
        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|three-photos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void deletingAPieceRevertsPortfolioToDraftIfUnderThreeVisiblePhotos() throws Exception {
        var fixerId = verifiedFixer("auth0|deleter");
        var p1 = publishAndReadId("auth0|deleter", "P1");
        publishAndReadId("auth0|deleter", "P2");
        publishAndReadId("auth0|deleter", "P3");

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|deleter")))
                .andExpect(status().isOk());

        // Delete one piece -> 204 No Content
        mvc.perform(delete("/media/me/portfolio/pieces/" + p1).with(identity("auth0|deleter")))
                .andExpect(status().isNoContent());

        // Piece is deleted from portfolio
        mvc.perform(get("/media/me/portfolio").with(identity("auth0|deleter")))
                .andExpect(jsonPath("$.pieces.length()").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // Portfolio status in db reverted to DRAFT
        var status = jdbc.queryForObject("SELECT status FROM fixer_portfolios WHERE fixer_user_id = ?", String.class, fixerId);
        assertThat(status).isEqualTo("DRAFT");

        // Public portfolio query now returns 404
        mvc.perform(get("/media/fixers/" + fixerId + "/portfolio").with(identity("auth0|deleter")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));
    }

    @Test
    void hidingRemovesThePieceFromThePublicPortfolioAndRevertsToDraftIfUnderThree() throws Exception {
        var fixer = verifiedFixer("auth0|curator");
        publishAndReadId("auth0|curator", "Visible1");
        publishAndReadId("auth0|curator", "Visible2");
        var p3 = publishAndReadId("auth0|curator", "ToHide");

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|curator")))
                .andExpect(status().isOk());

        // Hiding 1 piece leaves 2 visible (< 3) -> portfolio automatically reverts to DRAFT
        mvc.perform(post("/media/me/portfolio/pieces/" + p3 + "/hide").with(identity("auth0|curator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("HIDDEN"));

        // Public consultation returns 404 because portfolio reverted to DRAFT
        mvc.perform(get("/media/fixers/" + fixer + "/portfolio").with(identity("auth0|curator")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));

        // The owner still sees everything (3 pieces)
        mvc.perform(get("/media/me/portfolio").with(identity("auth0|curator")))
                .andExpect(jsonPath("$.pieces.length()").value(3))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // Showing piece restores it to PUBLIC, but does not publish automatically
        mvc.perform(post("/media/me/portfolio/pieces/" + p3 + "/show").with(identity("auth0|curator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PUBLIC"));

        // Must explicitly publish again
        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|curator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(get("/media/fixers/" + fixer + "/portfolio").with(identity("auth0|curator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void repeatingTheCurrentVisibilityIsAConflict() throws Exception {
        verifiedFixer("auth0|repeat");
        var piece = publishAndReadId("auth0|repeat", "Obra");

        mvc.perform(post("/media/me/portfolio/pieces/" + piece + "/show").with(identity("auth0|repeat")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIBILITY_UNCHANGED"));
    }

    @Test
    void aFixerCannotCurateOrDeleteAnotherPortfolioAndCannotTellThePieceExists() throws Exception {
        verifiedFixer("auth0|owner-fixer");
        verifiedFixer("auth0|other-fixer");
        var foreign = publishAndReadId("auth0|owner-fixer", "Ajena");

        // Foreign piece returns 404 PIECE_NOT_FOUND
        mvc.perform(post("/media/me/portfolio/pieces/" + foreign + "/hide").with(identity("auth0|other-fixer")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));
        mvc.perform(delete("/media/me/portfolio/pieces/" + foreign).with(identity("auth0|other-fixer")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));

        // Nonexistent piece returns identical 404 PIECE_NOT_FOUND
        var nonexistent = UUID.randomUUID();
        mvc.perform(post("/media/me/portfolio/pieces/" + nonexistent + "/hide").with(identity("auth0|other-fixer")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));
        mvc.perform(delete("/media/me/portfolio/pieces/" + nonexistent).with(identity("auth0|other-fixer")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));
    }

    @Test
    void aFixerCannotAttachAnotherUsersMedia() throws Exception {
        verifiedFixer("auth0|owner-media");
        verifiedFixer("auth0|thief");
        var mediaId = uploadAndConfirmJpeg("auth0|owner-media");

        // Attempting to publish another fixer's mediaId returns 404 MEDIA_NOT_FOUND
        publishPieceWithMedia("auth0|thief", mediaId, "Robada")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void mediaCannotBeReusedAcrossPieces() throws Exception {
        verifiedFixer("auth0|reuse");
        var mediaId = uploadAndConfirmJpeg("auth0|reuse");

        publishPieceWithMedia("auth0|reuse", mediaId, "Primera vez").andExpect(status().isCreated());

        // Attempting to reuse same mediaId returns 409 MEDIA_ALREADY_ATTACHED
        publishPieceWithMedia("auth0|reuse", mediaId, "Segunda vez")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_ALREADY_ATTACHED"));
    }

    @Test
    void confirmingMediaIsIdempotent() throws Exception {
        verifiedFixer("auth0|idempotent");
        var mediaId = uploadAndConfirmJpeg("auth0|idempotent");

        // Confirming again returns 200 with READY
        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity("auth0|idempotent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void confirmingInvalidMediaReturnsConflictAndDoesNotDegradeToNotFound() throws Exception {
        verifiedFixer("auth0|repeat-invalid");

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":8}";
        var uploadRes = mvc.perform(post("/media/uploads").with(identity("auth0|repeat-invalid"))
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn();

        var mediaId = UUID.fromString(mapper.readTree(uploadRes.getResponse().getContentAsString()).get("mediaId").asText());
        var objectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class, mediaId);

        // Put invalid bytes
        TestStorageConfiguration.instance().put(objectKey, TestStorageConfiguration.EXE_MAGIC, "image/jpeg");

        // First confirm -> 415 MEDIA_TYPE_NOT_ALLOWED
        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity("auth0|repeat-invalid")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_ALLOWED"));

        // Simulate physical file deletion after purge job
        TestStorageConfiguration.instance().delete(objectKey);

        // Subsequent confirm must be 409 MEDIA_INVALID, NEVER 404 MEDIA_NOT_FOUND
        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity("auth0|repeat-invalid")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_INVALID"));
    }

    @Test
    void contentTypeMismatchBetweenStorageAndDeclarationIsRejected() throws Exception {
        verifiedFixer("auth0|mismatch-type");

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":6}";
        var uploadRes = mvc.perform(post("/media/uploads").with(identity("auth0|mismatch-type"))
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn();

        var mediaId = UUID.fromString(mapper.readTree(uploadRes.getResponse().getContentAsString()).get("mediaId").asText());
        var objectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class, mediaId);

        // Put valid JPEG bytes but set storage metadata to image/png
        TestStorageConfiguration.instance().put(objectKey, TestStorageConfiguration.JPEG_MAGIC, "image/png");

        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity("auth0|mismatch-type")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_READY"));
    }

    @Test
    void invalidMagicBytesRejectedWith415() throws Exception {
        verifiedFixer("auth0|bad-bytes");

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":8}";
        var uploadRes = mvc.perform(post("/media/uploads").with(identity("auth0|bad-bytes"))
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn();

        var mediaId = UUID.fromString(mapper.readTree(uploadRes.getResponse().getContentAsString()).get("mediaId").asText());
        var objectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class, mediaId);

        // Put executable bytes instead of JPEG
        TestStorageConfiguration.instance().put(objectKey, TestStorageConfiguration.EXE_MAGIC, "image/jpeg");

        mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity("auth0|bad-bytes")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_ALLOWED"));

        var status = jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String.class, mediaId);
        assertThat(status).isEqualTo("INVALID");

        // Check that a purge job was recorded
        var jobs = jdbc.queryForList("SELECT job_type, status FROM media_deletion_jobs WHERE media_asset_id = ?", mediaId);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("job_type")).isEqualTo("INVALID_PURGE");
    }

    @Test
    void draftAndNonExistentPortfoliosReturnIndistinguishable404() throws Exception {
        var draftFixer = verifiedFixer("auth0|draft-fixer");
        publish("auth0|draft-fixer", "Borrador").andExpect(status().isCreated());
        verifiedFixer("auth0|active-reader");

        // Existing fixer with DRAFT portfolio -> 404 PORTFOLIO_NOT_FOUND
        mvc.perform(get("/media/fixers/" + draftFixer + "/portfolio").with(identity("auth0|active-reader")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));

        // Non-existent fixer -> identical 404 PORTFOLIO_NOT_FOUND
        mvc.perform(get("/media/fixers/" + UUID.randomUUID() + "/portfolio").with(identity("auth0|active-reader")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));
    }

    @Test
    void thePortfolioIsCappedAndTheLimitIsReportedAsAConflict() throws Exception {
        verifiedFixer("auth0|prolific");
        for (int index = 1; index <= PortfolioPolicy.MAX_PIECES; index++) {
            publish("auth0|prolific", "Obra" + index).andExpect(status().isCreated());
        }

        publish("auth0|prolific", "Excedente").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_FULL"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portfolio_pieces", Integer.class))
                .isEqualTo(PortfolioPolicy.MAX_PIECES);
    }

    @Test
    void twoSimultaneousFirstPublicationsNeitherCollideNorFail() throws Exception {
        var fixerId = verifiedFixer("auth0|first-concurrent");

        // Do not pre-create fixer_portfolios; verify first publication concurrency
        var m1 = uploadAndConfirmJpeg("auth0|first-concurrent");
        var m2 = uploadAndConfirmJpeg("auth0|first-concurrent");

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            Callable<Integer> first = () -> statusOfPublication("auth0|first-concurrent", m1, "Primera1", start);
            Callable<Integer> second = () -> statusOfPublication("auth0|first-concurrent", m2, "Primera2", start);
            var attempts = List.of(pool.submit(first), pool.submit(second));
            start.countDown();
            statuses = new ArrayList<>();
            for (var attempt : attempts) {
                statuses.add(attempt.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).as("a concurrent publication is 201 or 409, never 500")
                .allMatch(s -> s == 201 || s == 409);
        assertThat(statuses).as("at least one publication goes through").contains(201);

        var portfolioCount = jdbc.queryForObject(
                "SELECT count(*) FROM fixer_portfolios WHERE fixer_user_id = ?", Integer.class, fixerId);
        assertThat(portfolioCount).as("fixer_portfolios row must exist exactly once").isEqualTo(1);

        var positions = jdbc.queryForList(
                "SELECT display_position FROM portfolio_pieces WHERE fixer_user_id = ? ORDER BY display_position",
                Integer.class, fixerId);
        assertThat(positions).doesNotHaveDuplicates()
                .hasSize(java.util.Collections.frequency(statuses, 201));
    }

    @Test
    void concurrentAttachmentOfSameMediaIdYieldsOneCreationAndOneConflict() throws Exception {
        verifiedFixer("auth0|same-media-concurrent");
        var mediaId = uploadAndConfirmJpeg("auth0|same-media-concurrent");

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            Callable<Integer> first = () -> statusOfPublication("auth0|same-media-concurrent", mediaId, "Intento1", start);
            Callable<Integer> second = () -> statusOfPublication("auth0|same-media-concurrent", mediaId, "Intento2", start);
            var attempts = List.of(pool.submit(first), pool.submit(second));
            start.countDown();
            statuses = new ArrayList<>();
            for (var attempt : attempts) {
                statuses.add(attempt.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).contains(201);
        assertThat(statuses).contains(409);
        var pieceCount = jdbc.queryForObject(
                "SELECT count(*) FROM portfolio_pieces WHERE media_asset_id = ?", Integer.class, mediaId);
        assertThat(pieceCount).isEqualTo(1);
    }

    private int statusOfPublication(String subject, UUID mediaId, String title, CountDownLatch start) throws Exception {
        start.await(30, TimeUnit.SECONDS);
        try {
            return publishPieceWithMedia(subject, mediaId, title).andReturn().getResponse().getStatus();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"mediaId\":null}",
        "{\"mediaId\":\"not-a-uuid\"}",
        "{\"title\":\"\"}",
        "{\"mediaId\":\"00000000-0000-0000-0000-000000000000\",\"title\":\"  \"}",
        "no-es-json"})
    void malformedBodiesAreRejectedBeforeReachingTheDomain(String payload) throws Exception {
        verifiedFixer("auth0|malformed");

        mvc.perform(post("/media/me/portfolio/pieces").with(identity("auth0|malformed"))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portfolio_pieces", Integer.class)).isZero();
    }

    @Test
    void theStoredRowKeepsOnlyMediaAssetIdAndNoStorageKeyOrFileContent() throws Exception {
        verifiedFixer("auth0|keys-only");
        publish("auth0|keys-only", "Clave").andExpect(status().isCreated());

        var columns = jdbc.queryForList("SELECT * FROM portfolio_pieces").get(0).keySet().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList();
        assertThat(columns).contains("media_asset_id")
                .doesNotContain("storage_key", "content", "file", "bytes", "data");
    }

    @Test
    void findOrCreateForUpdatePreservesPublishedStatus() throws Exception {
        UUID fixerId = verifiedFixer("auth0|persists-published");
        publish("auth0|persists-published", "First piece").andExpect(status().isCreated());
        publish("auth0|persists-published", "Second piece").andExpect(status().isCreated());
        publish("auth0|persists-published", "Third piece").andExpect(status().isCreated());

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|persists-published")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        var portfolio = portfolios.findOrCreateForUpdate(fixerId);
        assertThat(portfolio.status().name()).isEqualTo("PUBLISHED");
    }

    @Test
    void ownPortfolioWhenNonExistentReturnsDraftWithNullPublishedAtAndEmptyPieces() throws Exception {
        var fixerId = verifiedFixer("auth0|brand-new-fixer");

        mvc.perform(get("/media/me/portfolio").with(identity("auth0|brand-new-fixer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixerUserId").value(fixerId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishedAt").doesNotExist())
                .andExpect(jsonPath("$.pieces").isArray())
                .andExpect(jsonPath("$.pieces.length()").value(0));
    }

    @Test
    void ownPortfolioInDraftReturnsDraftStatus() throws Exception {
        var fixerId = verifiedFixer("auth0|draft-owner");
        publish("auth0|draft-owner", "Foto 1").andExpect(status().isCreated());

        mvc.perform(get("/media/me/portfolio").with(identity("auth0|draft-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixerUserId").value(fixerId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishedAt").doesNotExist())
                .andExpect(jsonPath("$.pieces.length()").value(1));
    }

    @Test
    void ownPortfolioPublishedReturnsPublishedStatusAndPublishedAt() throws Exception {
        var fixerId = verifiedFixer("auth0|pub-owner");
        publish("auth0|pub-owner", "P1").andExpect(status().isCreated());
        publish("auth0|pub-owner", "P2").andExpect(status().isCreated());
        publish("auth0|pub-owner", "P3").andExpect(status().isCreated());

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|pub-owner")))
                .andExpect(status().isOk());

        mvc.perform(get("/media/me/portfolio").with(identity("auth0|pub-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixerUserId").value(fixerId.toString()))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.pieces.length()").value(3));
    }

    @Test
    void ownPortfolioIncludesHiddenPieces() throws Exception {
        verifiedFixer("auth0|hide-check");
        publish("auth0|hide-check", "Visible").andExpect(status().isCreated());
        var hiddenId = publishAndReadId("auth0|hide-check", "ToHide");

        mvc.perform(post("/media/me/portfolio/pieces/" + hiddenId + "/hide").with(identity("auth0|hide-check")))
                .andExpect(status().isOk());

        mvc.perform(get("/media/me/portfolio").with(identity("auth0|hide-check")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pieces.length()").value(2))
                .andExpect(jsonPath("$.pieces[?(@.id == '" + hiddenId + "')].visibility").value("HIDDEN"));
    }

    @Test
    void activeUserCanConsultPublicPublishedPortfolio() throws Exception {
        var fixerId = verifiedFixer("auth0|public-fixer");
        publish("auth0|public-fixer", "P1").andExpect(status().isCreated());
        publish("auth0|public-fixer", "P2").andExpect(status().isCreated());
        publish("auth0|public-fixer", "P3").andExpect(status().isCreated());
        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|public-fixer")))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/bootstrap").with(identity("auth0|client-reader")))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/select-role").with(identity("auth0|client-reader"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/media/fixers/" + fixerId + "/portfolio").with(identity("auth0|client-reader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].readUrl").isNotEmpty());
    }

    @Test
    void suspendedUserCannotConsultPublicPortfolio() throws Exception {
        var fixerId = verifiedFixer("auth0|target-fixer");
        publish("auth0|target-fixer", "P1").andExpect(status().isCreated());
        publish("auth0|target-fixer", "P2").andExpect(status().isCreated());
        publish("auth0|target-fixer", "P3").andExpect(status().isCreated());
        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|target-fixer")))
                .andExpect(status().isOk());

        var readerCreated = mvc.perform(post("/auth/bootstrap").with(identity("auth0|suspended-reader")))
                .andExpect(status().isCreated()).andReturn();
        var readerId = UUID.fromString(mapper.readTree(readerCreated.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/auth/select-role").with(identity("auth0|suspended-reader"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", readerId);

        mvc.perform(get("/media/fixers/" + fixerId + "/portfolio").with(identity("auth0|suspended-reader")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void portfolioConsultationsNeverExposeStorageKeysOrBucket() throws Exception {
        var fixerId = verifiedFixer("auth0|no-leak-fixer");
        publish("auth0|no-leak-fixer", "P1").andExpect(status().isCreated());
        publish("auth0|no-leak-fixer", "P2").andExpect(status().isCreated());
        publish("auth0|no-leak-fixer", "P3").andExpect(status().isCreated());
        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|no-leak-fixer")))
                .andExpect(status().isOk());

        mvc.perform(get("/media/me/portfolio").with(identity("auth0|no-leak-fixer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..storageKey").doesNotExist())
                .andExpect(jsonPath("$..objectKey").doesNotExist())
                .andExpect(jsonPath("$..bucket").doesNotExist());

        mvc.perform(get("/media/fixers/" + fixerId + "/portfolio").with(identity("auth0|no-leak-fixer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..storageKey").doesNotExist())
                .andExpect(jsonPath("$..objectKey").doesNotExist())
                .andExpect(jsonPath("$..bucket").doesNotExist());
    }
}
