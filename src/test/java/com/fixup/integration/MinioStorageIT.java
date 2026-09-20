package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfiguration.class)
@Testcontainers
class MinioStorageIT {
    private static final byte[] VALID_JPEG = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03, 0x04, 0x05};

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>("quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e")
            .withEnv("MINIO_ROOT_USER", "fixup")
            .withEnv("MINIO_ROOT_PASSWORD", "development-only-secret")
            .withCommand("server /data --console-address :9001")
            .withExposedPorts(9000, 9001)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        registry.add("fixup.storage.endpoint", () -> endpoint);
        registry.add("fixup.storage.public-endpoint", () -> endpoint);
        registry.add("fixup.storage.access-key", () -> "fixup");
        registry.add("fixup.storage.secret-key", () -> "development-only-secret");
        registry.add("fixup.storage.bucket", () -> "fixup-private");
        registry.add("fixup.storage.region", () -> "us-east-1");
        registry.add("fixup.storage.auto-create-bucket", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDb() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM media_deletion_jobs");
        jdbc.update("DELETE FROM portfolio_pieces");
        jdbc.update("DELETE FROM fixer_portfolios");
        jdbc.update("DELETE FROM fixer_verification_documents");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM fixer_profiles");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    private RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", "fixer@example.test")
                .claim("name", "Synthetic fixer"));
    }

    private UUID bootstrapFixer(String subject) throws Exception {
        var created = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        var id = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", id);
        return id;
    }

    @Test
    void completeUploadPublishReadAndDeleteFlowAgainstMinio() throws Exception {
        String subject = "auth0|minio-fixer";
        UUID fixerId = bootstrapFixer(subject);

        // Upload and publish 3 photos
        UUID firstPieceId = null;
        String firstReadUrl = null;
        String firstObjectKey = null;

        for (int i = 1; i <= 3; i++) {
            // 1. Request upload ticket
            var reqUpload = "{\"purpose\":\"FIXER_PORTFOLIO\",\"contentType\":\"image/jpeg\",\"sizeBytes\":" + VALID_JPEG.length + "}";
            var uploadRes = mvc.perform(post("/media/uploads").with(identity(subject))
                    .contentType(MediaType.APPLICATION_JSON).content(reqUpload))
                    .andExpect(status().isCreated()).andReturn();

            var uploadTree = mapper.readTree(uploadRes.getResponse().getContentAsString());
            UUID mediaId = UUID.fromString(uploadTree.get("mediaId").asText());
            String uploadUrl = uploadTree.get("uploadUrl").asText();

            // 2. Upload photo via presigned PUT from authorized origin
            var putReq = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .header("Origin", "http://localhost:4200")
                    .header("Content-Type", "image/jpeg")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(VALID_JPEG))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var putRes = http.send(putReq, HttpResponse.BodyHandlers.ofString());
            assertThat(putRes.statusCode()).as("MinIO PUT upload should succeed").isIn(200, 204);

            // 3. Confirm upload
            mvc.perform(post("/media/uploads/" + mediaId + "/confirm").with(identity(subject)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("READY"));

            // 4. Attach to portfolio
            var pieceRes = mvc.perform(post("/media/me/portfolio/pieces").with(identity(subject))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mediaId\":\"" + mediaId + "\",\"title\":\"Foto " + i + "\",\"description\":\"Desc " + i + "\"}"))
                    .andExpect(status().isCreated()).andReturn();

            var pieceTree = mapper.readTree(pieceRes.getResponse().getContentAsString());
            if (i == 1) {
                firstPieceId = UUID.fromString(pieceTree.get("id").asText());
                firstReadUrl = pieceTree.get("readUrl").asText();
                firstObjectKey = jdbc.queryForObject("SELECT object_key FROM media_assets WHERE id = ?", String.class, mediaId);
            }
        }
        assertThat(firstReadUrl).isNotEmpty();

        // 5. Publish portfolio
        mvc.perform(post("/media/me/portfolio/publish").with(identity(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // 6. Consult public portfolio and download using presigned GET
        var publicRes = mvc.perform(get("/media/fixers/" + fixerId + "/portfolio").with(identity(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();

        var publicTree = mapper.readTree(publicRes.getResponse().getContentAsString());
        String readUrlFromPublic = publicTree.get(0).get("readUrl").asText();
        assertThat(readUrlFromPublic).isNotEmpty();

        var getReq = HttpRequest.newBuilder()
                .uri(URI.create(readUrlFromPublic))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        var getRes = http.send(getReq, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(getRes.statusCode()).isEqualTo(200);
        assertThat(getRes.body()).isEqualTo(VALID_JPEG);

        // 7. Verify bucket is PRIVATE: anonymous direct access without signature is 403
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        var directReq = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/fixup-private/" + firstObjectKey))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        var directRes = http.send(directReq, HttpResponse.BodyHandlers.ofString());
        assertThat(directRes.statusCode()).as("Direct anonymous GET to private bucket must be 403").isEqualTo(403);

        // 8. Delete a piece
        mvc.perform(delete("/media/me/portfolio/pieces/" + firstPieceId).with(identity(subject)))
                .andExpect(status().isNoContent());

        // 9. Confirm that the portfolio reverted to DRAFT and is no longer public
        mvc.perform(get("/media/fixers/" + fixerId + "/portfolio").with(identity(subject)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_NOT_FOUND"));

        // 10. Confirm another user cannot modify or delete -> 404 PIECE_NOT_FOUND
        bootstrapFixer("auth0|other-guy");
        mvc.perform(delete("/media/me/portfolio/pieces/" + firstPieceId).with(identity("auth0|other-guy")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));

        // Nonexistent piece produces indistinguishable 404
        mvc.perform(delete("/media/me/portfolio/pieces/" + UUID.randomUUID()).with(identity("auth0|other-guy")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PIECE_NOT_FOUND"));
    }
}
