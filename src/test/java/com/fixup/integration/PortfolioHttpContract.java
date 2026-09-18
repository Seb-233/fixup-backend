package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.media.domain.PortfolioPolicy;
import java.util.UUID;
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
        jdbc.update("DELETE FROM portfolio_pieces");
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

    /**
     * FR-UC-16 owns the review that turns a profile VERIFIED. That flow lives on its own branch,
     * so the state is set directly here: this contract covers the portfolio, not the review.
     */
    private UUID verifiedFixer(String subject) throws Exception {
        var id = bootstrap(subject, "FIXER");
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", id);
        return id;
    }

    private String body(String kind, String key, String title, String description) {
        return "{\"kind\":\"" + kind + "\",\"storageKey\":\"" + key + "\",\"title\":\"" + title
                + "\",\"description\":" + (description == null ? "null" : "\"" + description + "\"") + "}";
    }

    private ResultActions publish(String subject, String title) throws Exception {
        return mvc.perform(post("/media/me/portfolio").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PHOTO", "fixers/portfolio/" + title + ".jpg", title, "Trabajo terminado")));
    }

    private UUID publishAndReadId(String subject, String title) throws Exception {
        var result = publish(subject, title).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void anonymousPortfolioRequestsReturnUniform401() throws Exception {
        for (var request : java.util.List.of(get("/media/me/portfolio"),
                post("/media/me/portfolio").contentType(MediaType.APPLICATION_JSON)
                        .content(body("PHOTO", "k", "t", null)),
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
                .andExpect(jsonPath("$.kind").value("PHOTO"))
                .andExpect(jsonPath("$.title").value("Cocina"))
                .andExpect(jsonPath("$.storageKey").value("fixers/portfolio/Cocina.jpg"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
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

        publish("auth0|pending-fixer", "Intento").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portfolio_pieces", Integer.class)).isZero();
    }

    @Test
    void anOwnerWithoutTheFixerRoleCannotPublish() throws Exception {
        bootstrap("auth0|owner", "OWNER");

        publish("auth0|owner", "Intento").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void aSuspendedAccountCannotPublishEvenWhenVerified() throws Exception {
        var id = verifiedFixer("auth0|suspended");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", id);

        publish("auth0|suspended", "Intento").andExpect(status().isForbidden());
    }

    @Test
    void hidingRemovesThePieceFromThePublicPortfolioAndShowingRestoresIt() throws Exception {
        var fixer = verifiedFixer("auth0|curator");
        var visible = publishAndReadId("auth0|curator", "Visible");
        var hidden = publishAndReadId("auth0|curator", "Oculta");

        mvc.perform(post("/media/me/portfolio/" + hidden + "/hide").with(identity("auth0|curator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("HIDDEN"));

        mvc.perform(get("/media/fixers/" + fixer + "/portfolio").with(identity("auth0|curator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(visible.toString()));

        // The owner still sees everything, hidden pieces included.
        mvc.perform(get("/media/me/portfolio").with(identity("auth0|curator")))
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(post("/media/me/portfolio/" + hidden + "/show").with(identity("auth0|curator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PUBLIC"));
        mvc.perform(get("/media/fixers/" + fixer + "/portfolio").with(identity("auth0|curator")))
                .andExpect(jsonPath("$.length()").value(2));
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
    void aFixerCannotCurateAnotherPortfolioAndCannotTellThePieceExists() throws Exception {
        verifiedFixer("auth0|owner-fixer");
        verifiedFixer("auth0|other-fixer");
        var foreign = publishAndReadId("auth0|owner-fixer", "Ajena");

        mvc.perform(post("/media/me/portfolio/" + foreign + "/hide").with(identity("auth0|other-fixer")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));
        mvc.perform(post("/media/me/portfolio/" + UUID.randomUUID() + "/hide")
                        .with(identity("auth0|other-fixer")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));
    }

    @Test
    void thePublicPortfolioOfAFixerWithoutPiecesIsEmpty() throws Exception {
        verifiedFixer("auth0|reader");

        mvc.perform(get("/media/fixers/" + UUID.randomUUID() + "/portfolio").with(identity("auth0|reader")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
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

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"kind\":\"PHOTO\",\"storageKey\":\"k\"}",
        "{\"kind\":\"PHOTO\",\"storageKey\":\"\",\"title\":\"t\"}",
        "{\"kind\":\"PHOTO\",\"storageKey\":\"k\",\"title\":\"  \"}",
        "{\"kind\":\"GIF\",\"storageKey\":\"k\",\"title\":\"t\"}",
        "{\"kind\":\"PHOTO\",\"storageKey\":\"k\",\"title\":\"t\",\"visibility\":\"PUBLIC\"}",
        "{\"kind\":\"PHOTO\",\"storageKey\":\"k\",\"title\":\"t\",\"content\":\"AAAA\"}",
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
    void aTitleLongerThanTheLimitIsRejectedByBeanValidation() throws Exception {
        verifiedFixer("auth0|long-title");

        mvc.perform(post("/media/me/portfolio").with(identity("auth0|long-title"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PHOTO", "k", "t".repeat(PortfolioPolicy.TITLE_MAX + 1), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theStoredRowKeepsOnlyTheStorageKeyAndNoFileContent() throws Exception {
        verifiedFixer("auth0|keys-only");
        publish("auth0|keys-only", "Clave").andExpect(status().isCreated());

        var columns = jdbc.queryForList("SELECT * FROM portfolio_pieces").get(0).keySet().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList();
        assertThat(columns).contains("storage_key")
                .doesNotContain("content", "file", "bytes", "data");
        assertThat(jdbc.queryForObject("SELECT storage_key FROM portfolio_pieces", String.class))
                .isEqualTo("fixers/portfolio/Clave.jpg");
    }
}
