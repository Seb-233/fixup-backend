package com.fixup.media;

import com.fixup.integration.TestJwtConfiguration;
import com.fixup.integration.TestStorageConfiguration;
import com.fixup.media.application.MediaDeletionService;
import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.infrastructure.MediaDeletionJobJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "fixup.media.deletion.enabled=true",
        "fixup.media.deletion.polling-delay=24h"
})
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
class MediaDeletionAfterCommitTest {

    @Autowired MediaDeletionService deletionService;
    @Autowired MediaDeletionJobJpaRepository jobRepository;
    @Autowired MediaAssets mediaAssets;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;

    private UUID ownerId;
    private final TestStorageConfiguration.InMemoryObjectStorage storage = TestStorageConfiguration.instance();

    @BeforeEach
    @AfterEach
    void resetDatabase() {
        storage.clear();
        jdbc.update("DELETE FROM media_deletion_jobs");
        jdbc.update("DELETE FROM portfolio_pieces");
        jdbc.update("DELETE FROM fixer_portfolios");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM fixer_profiles");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");

        ownerId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ownerId, "auth0|" + ownerId, "owner@example.test", "Owner", "ACTIVE", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    }

    private UUID createAsset(String objectKey) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        var asset = MediaAsset.createPending(id, ownerId, "FIXER_PORTFOLIO", objectKey, "image/jpeg", 100,
                now.plusSeconds(900), now).markReady(now).markAttached().markDeletionPending();
        mediaAssets.save(asset);
        return id;
    }

    @Test
    void afterCommitEventProcessesJobWhenTransactionCommits() {
        String key = "event-commit/photo.jpg";
        storage.put(key, new byte[]{1, 2, 3}, "image/jpeg");
        UUID assetId = createAsset(key);

        tx.execute(status -> {
            deletionService.schedulePieceDeletion(assetId, key);
            return null;
        });

        // Transaction committed -> AFTER_COMMIT listener processed the job
        assertThat(storage.exists(key)).isFalse();
        var jobs = jobRepository.findAll();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(jobs.get(0).getClaimToken()).isNull();
        assertThat(mediaAssets.findById(assetId).orElseThrow().status()).isEqualTo(MediaAssetStatus.DELETED);
    }

    @Test
    void afterCommitEventDoesNotExecuteWhenTransactionRollsBack() {
        String key = "event-rollback/photo.jpg";
        storage.put(key, new byte[]{1, 2, 3}, "image/jpeg");
        UUID assetId = createAsset(key);

        try {
            tx.execute(status -> {
                deletionService.schedulePieceDeletion(assetId, key);
                throw new RuntimeException("Forced transaction rollback");
            });
        } catch (RuntimeException ignored) {
        }

        // Transaction rolled back -> No job persisted, listener did not execute, storage intact
        Integer count = jdbc.queryForObject("SELECT count(*) FROM media_deletion_jobs WHERE object_key = ?", Integer.class, key);
        assertThat(count).isZero();
        assertThat(storage.exists(key)).isTrue();
        assertThat(mediaAssets.findById(assetId).orElseThrow().status()).isEqualTo(MediaAssetStatus.DELETION_PENDING);
    }
}
