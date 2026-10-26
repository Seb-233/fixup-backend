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
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:media-after-commit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "fixup.media.deletion.enabled=true",
        "fixup.media.deletion.polling-delay=24h"
})
@Import({TestJwtConfiguration.class, MediaDeletionAfterCommitTest.IsolatedFixtures.class})
class MediaDeletionAfterCommitTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedFixtures {
        @Bean
        @Primary
        Clock deletionClock() {
            // A repeated clock tick must not become a future retry when SQL rounds to microseconds.
            return Clock.fixed(Instant.parse("2026-10-24T12:00:00.123456789Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        TestStorageConfiguration.InMemoryObjectStorage deletionStorage() {
            return new TestStorageConfiguration.InMemoryObjectStorage();
        }
    }

    @Autowired MediaDeletionService deletionService;
    @Autowired MediaDeletionJobJpaRepository jobRepository;
    @Autowired MediaAssets mediaAssets;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    private UUID ownerId;
    @Autowired TestStorageConfiguration.InMemoryObjectStorage storage;

    @BeforeEach
    @AfterEach
    void resetDatabase() {
        storage.clear();
        databaseCleaner.clean();

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
        String key = "event-commit/" + UUID.randomUUID() + ".jpg";
        storage.put(key, new byte[]{1, 2, 3}, "image/jpeg");
        UUID assetId = createAsset(key);

        tx.execute(status -> {
            deletionService.schedulePieceDeletion(assetId, key);
            assertThat(storage.exists(key)).isTrue();
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
        String key = "event-rollback/" + UUID.randomUUID() + ".jpg";
        storage.put(key, new byte[]{1, 2, 3}, "image/jpeg");
        UUID assetId = createAsset(key);

        assertThatThrownBy(() -> tx.execute(status -> {
                deletionService.schedulePieceDeletion(assetId, key);
                throw new RuntimeException("Forced transaction rollback");
            })).isInstanceOf(RuntimeException.class).hasMessage("Forced transaction rollback");

        // Transaction rolled back -> No job persisted, listener did not execute, storage intact
        Integer count = jdbc.queryForObject("SELECT count(*) FROM media_deletion_jobs WHERE object_key = ?", Integer.class, key);
        assertThat(count).isZero();
        assertThat(storage.exists(key)).isTrue();
        assertThat(mediaAssets.findById(assetId).orElseThrow().status()).isEqualTo(MediaAssetStatus.DELETION_PENDING);
    }
}
