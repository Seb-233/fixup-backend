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
        // The exported surface is pinned by name: an endpoint may only appear here deliberately.
        assertThat(paths.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/auth/bootstrap", "/auth/me", "/auth/select-role",
                "/media/me/portfolio", "/media/me/portfolio/{pieceId}/hide",
                "/media/me/portfolio/{pieceId}/show", "/media/fixers/{fixerUserId}/portfolio");
        for (var endpoint : new String[][]{
                {"/auth/bootstrap", "post", "200"}, {"/auth/me", "get", "200"},
                {"/auth/select-role", "post", "200"},
                {"/media/me/portfolio", "post", "201"}, {"/media/me/portfolio", "get", "200"},
                {"/media/me/portfolio/{pieceId}/hide", "post", "200"},
                {"/media/me/portfolio/{pieceId}/show", "post", "200"},
                {"/media/fixers/{fixerUserId}/portfolio", "get", "200"}}) {
            var operation = paths.get(endpoint[0]).get(endpoint[1]);
            assertThat(operation.get("security").toString()).contains("bearerAuth");
            for (String code : new String[]{endpoint[2], "400", "401", "403", "409"}) {
                assertThat(operation.get("responses").has(code)).as(endpoint[0] + " status " + code).isTrue();
            }
            assertThat(operation.get("responses").has("402")).isFalse();
        }
        assertThat(paths.get("/auth/bootstrap").get("post").get("responses").has("201")).isTrue();
        assertThat(contract.at("/components/schemas/UserResponse/properties/email/type").toString()).contains("null");
        assertThat(contract.at("/components/schemas/BootstrapResponse/properties/displayName/type").toString()).contains("null");
        assertThat(contract.at("/components/schemas/PieceRequest/properties/kind/enum").toString())
                .contains("PHOTO", "VIDEO");
        assertThat(contract.at("/components/schemas/PieceResponse/properties/visibility/enum").toString())
                .contains("PUBLIC", "HIDDEN");
        // No media content crosses this API: the request carries a storage key only.
        assertThat(contract.at("/components/schemas/PieceRequest/properties").toString())
                .contains("storageKey").doesNotContain("content", "file", "bytes");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "openapi.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(contract) + System.lineSeparator());
    }
}
