package com.fixup.integration;

import com.fixup.media.application.ClaimedDeletionJob;
import com.fixup.media.application.MediaDeletionJobClaimer;
import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.MediaDeletionJobType;
import com.fixup.media.infrastructure.MediaDeletionJobEntity;
import com.fixup.media.infrastructure.MediaDeletionJobJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@Testcontainers
class MediaDeletionConcurrencyIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MediaDeletionJobClaimer claimer;
    @Autowired MediaDeletionJobJpaRepository jobRepository;
    @Autowired MediaAssets mediaAssets;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private UUID ownerId;

    @BeforeEach
    @AfterEach
    void resetDatabase() {
        jdbc.update("DELETE FROM media_deletion_jobs");
        jdbc.update("DELETE FROM portfolio_pieces");
        jdbc.update("DELETE FROM fixer_portfolios");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM fixer_verification_documents");
        jdbc.update("DELETE FROM fixer_profiles");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }

    private UUID createPendingJob(String objectKey) {
        ownerId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ownerId, "auth0|" + ownerId, "owner@example.test", "Owner", "ACTIVE", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        UUID assetId = UUID.randomUUID();
        var asset = MediaAsset.createPending(assetId, ownerId, "FIXER_PORTFOLIO", objectKey, "image/jpeg", 100,
                now.plusSeconds(900), now).markReady(now).markAttached().markDeletionPending();
        mediaAssets.save(asset);

        UUID jobId = UUID.randomUUID();
        var job = MediaDeletionJobEntity.pending(jobId, assetId, objectKey, MediaDeletionJobType.PIECE_DELETION, 5, now);
        jobRepository.saveAndFlush(job);
        return jobId;
    }

    @Test
    void twoConcurrentWorkersAttemptingSameJobOnlyOneGetsClaim() throws Exception {
        UUID jobId = createPendingJob("concurrent-test/job1.jpg");
        Instant now = Instant.now(clock);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        var threadPool = Executors.newFixedThreadPool(2);
        Callable<Optional<ClaimedDeletionJob>> task = () -> {
            startGate.await(5, TimeUnit.SECONDS);
            try {
                return claimer.claimSpecificJob(jobId, now);
            } finally {
                doneGate.countDown();
            }
        };

        Future<Optional<ClaimedDeletionJob>> future1 = threadPool.submit(task);
        Future<Optional<ClaimedDeletionJob>> future2 = threadPool.submit(task);

        startGate.countDown();
        assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
        threadPool.shutdown();

        Optional<ClaimedDeletionJob> res1 = future1.get();
        Optional<ClaimedDeletionJob> res2 = future2.get();

        assertThat(res1.isPresent() ^ res2.isPresent())
                .as("Exactly one worker must obtain the claim").isTrue();

        ClaimedDeletionJob winner = res1.isPresent() ? res1.get() : res2.get();
        assertThat(winner.jobId()).isEqualTo(jobId);
        assertThat(winner.claimToken()).isNotNull();

        var entity = jobRepository.findById(jobId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("PROCESSING");
        assertThat(entity.getClaimToken()).isEqualTo(winner.claimToken());
        assertThat(entity.getLockedAt()).isNotNull();
    }

    @Test
    void twoConcurrentWorkersBatchClaimDoNotReceiveSameJobs() throws Exception {
        for (int i = 0; i < 6; i++) {
            createPendingJob("concurrent-test/batch" + i + ".jpg");
        }
        Instant now = Instant.now(clock);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        var threadPool = Executors.newFixedThreadPool(2);

        Callable<List<ClaimedDeletionJob>> task = () -> {
            startGate.await(5, TimeUnit.SECONDS);
            try {
                return claimer.claimNextBatch(now, 3);
            } finally {
                doneGate.countDown();
            }
        };

        Future<List<ClaimedDeletionJob>> future1 = threadPool.submit(task);
        Future<List<ClaimedDeletionJob>> future2 = threadPool.submit(task);

        startGate.countDown();
        assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
        threadPool.shutdown();

        List<ClaimedDeletionJob> batch1 = future1.get();
        List<ClaimedDeletionJob> batch2 = future2.get();

        List<UUID> claimedIds1 = batch1.stream().map(ClaimedDeletionJob::jobId).toList();
        List<UUID> claimedIds2 = batch2.stream().map(ClaimedDeletionJob::jobId).toList();

        assertThat(claimedIds1).doesNotContainAnyElementsOf(claimedIds2);
        assertThat(claimedIds1.size() + claimedIds2.size()).isEqualTo(6);
    }
}
