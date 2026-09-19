package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.media.domain.PortfolioPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Reused unchanged against H2 and PostgreSQL to keep the HTTP/security contract equivalent. */
abstract class PortfolioHttpContract {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        TestStorageConfiguration.instance().clear();
        jdbc.update("DELETE FROM media_deletion_jobs");
        jdbc.update("DELETE FROM portfolio_pieces");
        jdbc.update("DELETE FROM fixer_portfolios");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM fixer_profiles");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", "fixer@example.test")
                .claim("name", "Synthetic fixer"));
    }

    private UUID bootstrap(String subject, String role) throws Exception {
        var created = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        var id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private UUID verifiedFixer(String subject) throws Exception {
        var id = bootstrap(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", id);
        return id;
    }

    private UUID uploadAndConfirm(String subject, byte[] magicBytes, String contentType) throws Exception {
        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"" + contentType + "\",\"sizeBytes\":" + magicBytes.length + "}";
        var uploadRes = mvc.perform(post("/media/uploads").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn();

        var tree = mapper.readTree(uploadRes.getResponse().getContentAsString());
        var mediaId = UUID.fromString(tree.get("mediaId").asText());

        // Extract objectKey from database to store bytes in test storage
        var objectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class, mediaId);
        TestStorageConfiguration.instance().put(objectKey, magicBytes, contentType);

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
        return mvc.perform(post("/media/me/portfolio").with(identity(subject))
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
                post("/media/me/portfolio").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaId\":\"" + UUID.randomUUID() + "\",\"title\":\"t\"}"),
                post("/media/me/portfolio/publish"),
                post("/media/me/portfolio/unpublish"),
                delete("/media/me/portfolio/" + UUID.randomUUID()),
                post("/media/me/portfolio/" + UUID.randomUUID() + "/hide"),
                get("/media/fixers/" + UUID.randomUUID() + "/portfolio"))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
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
        publish("auth0|ordered", "Primera").andExpect(jsonPath("$.position").value(1));
        publish("auth0|ordered", "Segunda").andExpect(jsonPath("$.position").value(2));
        publish("auth0|ordered", "Tercera").andExpect(jsonPath("$.position").value(3));

        mvc.perform(get("/media/me/portfolio").with(identity("auth0|ordered")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].title").value("Primera"))
                .andExpect(jsonPath("$[2].title").value("Tercera"));
    }

    @Test
    void aPendingFixerCannotPublish() throws Exception {
        bootstrap("auth0|pending-fixer", "FIXER");

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":10}";
        mvc.perform(post("/media/uploads").with(identity("auth0|pending-fixer"))
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void anOwnerWithoutTheFixerRoleCannotPublish() throws Exception {
        bootstrap("auth0|owner", "OWNER");

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":10}";
        mvc.perform(post("/media/uploads").with(identity("auth0|owner"))
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void aSuspendedAccountCannotPublishEvenWhenVerified() throws Exception {
        var id = verifiedFixer("auth0|suspended");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", id);

        var req = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":10}";
        mvc.perform(post("/media/uploads").with(identity("auth0|suspended"))
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden());
    }

    @Test
    void portfolioRequiresAtLeastThreePhotosToPublish() throws Exception {
        verifiedFixer("auth0|three-photos");

        publish("auth0|three-photos", "Foto 1").andExpect(status().isCreated());
        publish("auth0|three-photos", "Foto 2").andExpect(status().isCreated());

        // With 2 photos, publish must be rejected with 409
        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|three-photos")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_INSUFFICIENT_PIECES"));

        // With 3 photos, publish succeeds
        publish("auth0|three-photos", "Foto 3").andExpect(status().isCreated());
        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|three-photos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void deletingAPieceLeavingLessThanThreeRevertsToDraft() throws Exception {
        var fixerId = verifiedFixer("auth0|deleter");

        var p1 = publishAndReadId("auth0|deleter", "P1");
        var p2 = publishAndReadId("auth0|deleter", "P2");
        var p3 = publishAndReadId("auth0|deleter", "P3");

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|deleter")))
                .andExpect(status().isOk());

        // Delete one piece -> 204 No Content
        mvc.perform(delete("/media/me/portfolio/" + p1).with(identity("auth0|deleter")))
                .andExpect(status().isNoContent());

        // Piece is deleted from portfolio
        mvc.perform(get("/media/me/portfolio").with(identity("auth0|deleter")))
                .andExpect(jsonPath("$.length()").value(2));

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
        var p1 = publishAndReadId("auth0|curator", "Visible1");
        var p2 = publishAndReadId("auth0|curator", "Visible2");
        var p3 = publishAndReadId("auth0|curator", "ToHide");

        mvc.perform(post("/media/me/portfolio/publish").with(identity("auth0|curator")))
                .andExpect(status().isOk());

        // Hiding 1 piece leaves 2 visible (< 3) -> portfolio automatically reverts to DRAFT
        mvc.perform(post("/media/me/portfolio/" + p3 + "/hide").with(identity("auth0|curator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("HIDDEN"));

        // Public consultation returns 404 because portfolio reverted to DRAFT
        mvc.perform(get("/media/fixers/" + fixer + "/portfolio").with(identity("auth0|curator")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));

        // The owner still sees everything (3 pieces)
        mvc.perform(get("/media/me/portfolio").with(identity("auth0|curator")))
                .andExpect(jsonPath("$.length()").value(3));

        // Showing piece restores it to PUBLIC, but does not publish automatically
        mvc.perform(post("/media/me/portfolio/" + p3 + "/show").with(identity("auth0|curator")))
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

        mvc.perform(post("/media/me/portfolio/" + piece + "/show").with(identity("auth0|repeat")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIBILITY_UNCHANGED"));
    }

    @Test
    void aFixerCannotCurateOrDeleteAnotherPortfolioAndCannotTellThePieceExists() throws Exception {
        verifiedFixer("auth0|owner-fixer");
        verifiedFixer("auth0|other-fixer");
        var foreign = publishAndReadId("auth0|owner-fixer", "Ajena");

        mvc.perform(post("/media/me/portfolio/" + foreign + "/hide").with(identity("auth0|other-fixer")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));
        mvc.perform(delete("/media/me/portfolio/" + foreign).with(identity("auth0|other-fixer")))
                .andExpect(status().isConflict())
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
    }

    @Test
    void thePublicPortfolioOfAFixerInDraftReturns404() throws Exception {
        verifiedFixer("auth0|reader");

        mvc.perform(get("/media/fixers/" + UUID.randomUUID() + "/portfolio").with(identity("auth0|reader")))
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
    void twoSimultaneousPublicationsNeitherCollideNorFail() throws Exception {
        verifiedFixer("auth0|concurrent");
        publish("auth0|concurrent", "Base").andExpect(status().isCreated());

        var m1 = uploadAndConfirmJpeg("auth0|concurrent");
        var m2 = uploadAndConfirmJpeg("auth0|concurrent");

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            Callable<Integer> first = () -> statusOfPublication("auth0|concurrent", m1, "Simultanea1", start);
            Callable<Integer> second = () -> statusOfPublication("auth0|concurrent", m2, "Simultanea2", start);
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
        var positions = jdbc.queryForList(
                "SELECT display_position FROM portfolio_pieces ORDER BY display_position", Integer.class);
        assertThat(positions).doesNotHaveDuplicates()
                .hasSize(1 + java.util.Collections.frequency(statuses, 201));
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

        mvc.perform(post("/media/me/portfolio").with(identity("auth0|malformed"))
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
}
