package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@Import(TestJwtConfiguration.class)
class OpenApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springdoc.webmvc.api.OpenApiWebMvcResource openApi;

    @Test
    void exportsDevelopmentContractWithoutPublicDocumentationEndpoint() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()))
                .andExpect(status().isForbidden());
        // Generate the contract directly; do not bypass or weaken the production filter chain.
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/v3/api-docs");
        var contract = mapper.readTree(openApi.openapiJson(request, "/v3/api-docs", java.util.Locale.ROOT));
        assertThat(contract.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(contract.at("/components/schemas/Role/enum").toString()).contains("PLATFORM_ADMIN", "OWNER", "FIXER");
        assertThat(contract.at("/components/schemas/UserStatus/enum").toString()).contains("ACTIVE", "SUSPENDED", "DISABLED");
        var paths = contract.get("paths");
        assertThat(paths.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/auth/bootstrap", "/auth/me", "/auth/select-role",
                "/fixers/me/verification", "/fixers/me/verification/documents",
                "/fixers/{fixerUserId}/verification/approve", "/fixers/{fixerUserId}/verification/reject",
                "/media/uploads", "/media/uploads/{mediaId}/confirm",
                "/media/me/portfolio", "/media/me/portfolio/pieces",
                "/media/me/portfolio/publish", "/media/me/portfolio/unpublish",
                "/media/me/portfolio/pieces/{pieceId}", "/media/me/portfolio/pieces/{pieceId}/hide",
                "/media/me/portfolio/pieces/{pieceId}/show", "/media/fixers/{fixerUserId}/portfolio");
        assertThat(paths.fieldNames()).toIterable().doesNotContain(
                "/media/me/portfolio/{pieceId}",
                "/media/me/portfolio/{pieceId}/hide",
                "/media/me/portfolio/{pieceId}/show");
        assertThat(paths.get("/media/me/portfolio").has("post")).as("Old POST /media/me/portfolio must not exist").isFalse();
        for (var endpoint : new String[][]{
                {"/auth/bootstrap", "post", "200"}, {"/auth/me", "get", "200"},
                {"/auth/select-role", "post", "200"},
                {"/fixers/me/verification", "get", "200"},
                {"/fixers/me/verification/documents", "post", "200"},
                {"/fixers/{fixerUserId}/verification/approve", "post", "204"},
                {"/fixers/{fixerUserId}/verification/reject", "post", "204"},
                {"/media/uploads", "post", "201"},
                {"/media/uploads/{mediaId}/confirm", "post", "200"},
                {"/media/me/portfolio/pieces", "post", "201"}, {"/media/me/portfolio", "get", "200"},
                {"/media/me/portfolio/publish", "post", "200"},
                {"/media/me/portfolio/unpublish", "post", "200"},
                {"/media/me/portfolio/pieces/{pieceId}", "delete", "204"},
                {"/media/me/portfolio/pieces/{pieceId}/hide", "post", "200"},
                {"/media/me/portfolio/pieces/{pieceId}/show", "post", "200"},
                {"/media/fixers/{fixerUserId}/portfolio", "get", "200"}}) {
            var operation = paths.get(endpoint[0]).get(endpoint[1]);
            assertThat(operation.get("security").toString()).contains("bearerAuth");
            for (String code : new String[]{endpoint[2], "400", "401", "403", "409"}) {
                assertThat(operation.get("responses").has(code)).as(endpoint[0] + " status " + code).isTrue();
            }
            if (endpoint[0].startsWith("/media")) {
                assertThat(operation.get("responses").has("404")).as(endpoint[0] + " status 404").isTrue();
            }
            assertThat(operation.get("responses").has("402")).isFalse();
        }
        for (String piecePath : java.util.List.of(
                "/media/me/portfolio/pieces/{pieceId}",
                "/media/me/portfolio/pieces/{pieceId}/hide",
                "/media/me/portfolio/pieces/{pieceId}/show")) {
            var pieceOp = piecePath.contains("hide") || piecePath.contains("show")
                    ? paths.get(piecePath).get("post")
                    : paths.get(piecePath).get("delete");
            assertThat(pieceOp.get("responses").has("404")).isTrue();
            assertThat(pieceOp.get("responses").get("404").get("description").asText()).contains("PIECE_NOT_FOUND");
        }
        assertThat(paths.get("/auth/bootstrap").get("post").get("responses").has("201")).isTrue();
        assertThat(contract.at("/components/schemas/UserResponse/properties/email/type").toString()).contains("null");
        assertThat(contract.at("/components/schemas/BootstrapResponse/properties/displayName/type").toString()).contains("null");
        assertThat(contract.at("/components/schemas/FixerVerificationDocumentType/enum").toString())
                .contains("ID_CARD", "TRADE_CERTIFICATE");
        assertThat(contract.at("/components/schemas/VerificationResponse/properties/submittedAt/type").toString())
                .contains("null");
        // No document content crosses this API: the request carries storage keys only.
        assertThat(contract.at("/components/schemas/DocumentRequest/properties").toString())
                .contains("storageKey").doesNotContain("content", "file");
        // PieceRequest now references mediaId instead of raw storageKey or kind
        assertThat(contract.at("/components/schemas/PieceRequest/properties").toString())
                .contains("mediaId", "title")
                .doesNotContain("storageKey", "kind", "content", "file", "bytes");
        assertThat(contract.at("/components/schemas/PieceResponse/properties/visibility/enum").toString())
                .contains("PUBLIC", "HIDDEN");
        assertThat(contract.at("/components/schemas/PieceResponse/properties").toString())
                .contains("mediaId", "readUrl")
                .doesNotContain("storageKey", "kind");
        Files.createDirectories(Path.of("target"));
        Files.createDirectories(Path.of("docs"));
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(contract) + System.lineSeparator();
        Files.writeString(Path.of("target", "openapi.json"), json);
        Files.writeString(Path.of("docs", "openapi.json"), json);
    }
}
