package com.fixup.media;

import com.fixup.integration.TestJwtConfiguration;
import com.fixup.integration.TestStorageConfiguration;
import com.fixup.media.application.ClaimedDeletionJob;
import com.fixup.media.application.MediaDeletionJobClaimer;
import com.fixup.media.application.MediaDeletionJobExecutor;
import com.fixup.media.application.MediaDeletionJobFinalizer;
import com.fixup.media.application.MediaDeletionService;
import com.fixup.media.application.MediaDeletionWorker;
import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.infrastructure.MediaDeletionJobJpaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class, MediaDeletionWorkerTest.ClockConfig.class})
class MediaDeletionWorkerTest {

    public static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-19T10:00:00Z");
        private final ZoneId zone = ZoneOffset.UTC;

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        public void set(Instant instant) {
            this.now = instant;
        }

        public void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }
    }

    @TestConfiguration
    static class ClockConfig {
        private final MutableClock testClock = new MutableClock();

        @Bean
        @Primary
        Clock testClock() {
            return testClock;
        }

        @Bean
        MutableClock mutableClock() {
            return testClock;
        }
    }

    @Autowired MutableClock clock;
    @Autowired MediaDeletionJobClaimer claimer;
    @Autowired MediaDeletionJobExecutor executor;
    @Autowired MediaDeletionJobFinalizer finalizer;
    @Autowired MediaDeletionWorker worker;
    @Autowired MediaDeletionService deletionService;
    @Autowired MediaDeletionJobJpaRepository jobRepository;
    @Autowired MediaAssets mediaAssets;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    private UUID ownerId;
    private final TestStorageConfiguration.InMemoryObjectStorage storage = TestStorageConfiguration.instance();

    @BeforeEach
    void setUp() {
        storage.clear();
        databaseCleaner.clean();

        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ownerId, "auth0|" + ownerId, "owner@example.test", "Owner", "ACTIVE", clock.instant(), clock.instant());
    }

    @AfterEach
    void tearDown() {
        storage.clear();
        databaseCleaner.clean();
    }

    private MediaAsset createAsset(MediaAssetStatus status, String objectKey) {
        UUID id = UUID.randomUUID();
        var asset = MediaAsset.createPending(id, ownerId, "FIXER_PORTFOLIO", objectKey, "image/jpeg", 100,
                clock.instant().plusSeconds(900), clock.instant());
        if (status == MediaAssetStatus.READY) {
            asset = asset.markReady(clock.instant());
        } else if (status == MediaAssetStatus.INVALID) {
            asset = asset.markInvalid();
        } else if (status == MediaAssetStatus.DELETION_PENDING) {
            asset = asset.markReady(clock.instant()).markAttached().markDeletionPending();
        } else if (status == MediaAssetStatus.DELETED) {
            asset = asset.markReady(clock.instant()).markAttached().markDeletionPending().markDeleted();
        }
        mediaAssets.save(asset);
        return asset;
    }

    @Test
    void twoWorkersAttemptingToClaimSameJobOnlyOneGetsClaim() {
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, "key1").id();
        deletionService.schedulePieceDeletion(assetId, "key1");
        var jobEntity = jobRepository.findAll().get(0);

        var claim1 = claimer.claimSpecificJob(jobEntity.getId(), clock.instant());
        var claim2 = claimer.claimSpecificJob(jobEntity.getId(), clock.instant());

        assertThat(claim1).isPresent();
        assertThat(claim2).isEmpty();
        assertThat(claim1.get().claimToken()).isNotNull();
    }

    @Test
    void workerWithOldClaimTokenCannotFinalizeJobRecoveredByAnother() {
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, "key2").id();
        deletionService.schedulePieceDeletion(assetId, "key2");
        UUID jobId = jobRepository.findAll().get(0).getId();

        ClaimedDeletionJob worker1Claim = claimer.claimSpecificJob(jobId, clock.instant()).orElseThrow();

        // Simulate worker 1 stalled and lock timed out (5 minutes)
        clock.advance(Duration.ofMinutes(10));
        int recovered = claimer.recoverStaleJobs(clock.instant(), Duration.ofMinutes(5));
        assertThat(recovered).isEqualTo(1);

        // Worker 2 claims the recovered job
        ClaimedDeletionJob worker2Claim = claimer.claimSpecificJob(jobId, clock.instant()).orElseThrow();
        assertThat(worker2Claim.claimToken()).isNotEqualTo(worker1Claim.claimToken());

        // Worker 1 tries to complete with stale claimToken -> rejected
        boolean w1Result = finalizer.completeJob(worker1Claim);
        assertThat(w1Result).isFalse();

        // Worker 2 completes with fresh claimToken -> accepted
        boolean w2Result = finalizer.completeJob(worker2Claim);
        assertThat(w2Result).isTrue();

        var finished = jobRepository.findById(jobId).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo("COMPLETED");
        assertThat(finished.getClaimToken()).isNull();
    }

    @Test
    void staleProcessingJobReturnsToPending() {
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, "key3").id();
        deletionService.schedulePieceDeletion(assetId, "key3");
        UUID jobId = jobRepository.findAll().get(0).getId();

        claimer.claimSpecificJob(jobId, clock.instant()).orElseThrow();
        var claimed = jobRepository.findById(jobId).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo("PROCESSING");
        assertThat(claimed.getClaimToken()).isNotNull();

        // Advance beyond lock timeout
        clock.advance(Duration.ofMinutes(6));
        int recovered = claimer.recoverStaleJobs(clock.instant(), Duration.ofMinutes(5));
        assertThat(recovered).isEqualTo(1);

        var restored = jobRepository.findById(jobId).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo("PENDING");
        assertThat(restored.getClaimToken()).isNull();
        assertThat(restored.getLockedAt()).isNull();
    }

    @Test
    void failedWithAttemptsLessThanMaxAttemptsIsReclaimed() {
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, "key4").id();
        deletionService.schedulePieceDeletion(assetId, "key4");
        UUID jobId = jobRepository.findAll().get(0).getId();

        var claim = claimer.claimSpecificJob(jobId, clock.instant()).orElseThrow();
        finalizer.failJob(claim, new RuntimeException("Simulated failure"));

        var failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isNotNull();

        // Advance past nextAttemptAt
        clock.advance(Duration.ofMinutes(1));
        var reclaimed = claimer.claimNextBatch(clock.instant(), 10);
        assertThat(reclaimed).extracting(ClaimedDeletionJob::jobId).contains(jobId);
    }

    @Test
    void failedWithAttemptsEqualToMaxAttemptsIsNotClaimed() {
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, "key5").id();
        deletionService.schedulePieceDeletion(assetId, "key5");
        UUID jobId = jobRepository.findAll().get(0).getId();

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(10));
            var claim = claimer.claimSpecificJob(jobId, clock.instant()).orElseThrow();
            finalizer.failJob(claim, new RuntimeException("Failure " + i));
        }

        var exhausted = jobRepository.findById(jobId).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo("FAILED");
        assertThat(exhausted.getAttempts()).isEqualTo(5);
        assertThat(exhausted.getNextAttemptAt()).isNull();

        clock.advance(Duration.ofHours(24));
        var batch = claimer.claimNextBatch(clock.instant(), 10);
        assertThat(batch).isEmpty();
    }

    @Test
    void backoffCalculatedDeterministicallyUsingClock() {
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, "key6").id();
        deletionService.schedulePieceDeletion(assetId, "key6");
        UUID jobId = jobRepository.findAll().get(0).getId();

        Instant start = clock.instant();

        // Attempt 1: backoff = 30s * 1 = 30s
        var claim1 = claimer.claimSpecificJob(jobId, start).orElseThrow();
        finalizer.failJob(claim1, new RuntimeException("Fail 1"));
        var j1 = jobRepository.findById(jobId).orElseThrow();
        assertThat(j1.getNextAttemptAt()).isEqualTo(start.plusSeconds(30));

        // Advance to next attempt
        clock.set(start.plusSeconds(31));
        var claim2 = claimer.claimSpecificJob(jobId, clock.instant()).orElseThrow();
        finalizer.failJob(claim2, new RuntimeException("Fail 2"));
        var j2 = jobRepository.findById(jobId).orElseThrow();
        // Attempt 2: backoff = 30s * 2 = 60s
        assertThat(j2.getNextAttemptAt()).isEqualTo(clock.instant().plusSeconds(60));
    }

    @Test
    void invalidPurgeCompletedPreservesMediaAssetInvalid() {
        String key = "portfolio/invalid-file.jpg";
        storage.put(key, new byte[] {0x00, 0x01}, "image/jpeg");
        UUID assetId = createAsset(MediaAssetStatus.INVALID, key).id();

        deletionService.scheduleInvalidObjectPurge(assetId, key);
        UUID jobId = jobRepository.findAll().get(0).getId();

        worker.triggerManualCycle();

        assertThat(storage.exists(key)).isFalse();
        var finishedJob = jobRepository.findById(jobId).orElseThrow();
        assertThat(finishedJob.getStatus()).isEqualTo("COMPLETED");

        var asset = mediaAssets.findById(assetId).orElseThrow();
        assertThat(asset.status()).as("Asset must remain INVALID after purge").isEqualTo(MediaAssetStatus.INVALID);
    }

    @Test
    void pieceDeletionCompletedChangesDeletionPendingToDeleted() {
        String key = "portfolio/normal-piece.jpg";
        storage.put(key, new byte[] {0x00, 0x01}, "image/jpeg");
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, key).id();

        deletionService.schedulePieceDeletion(assetId, key);
        UUID jobId = jobRepository.findAll().get(0).getId();

        worker.triggerManualCycle();

        assertThat(storage.exists(key)).isFalse();
        var finishedJob = jobRepository.findById(jobId).orElseThrow();
        assertThat(finishedJob.getStatus()).isEqualTo("COMPLETED");

        var asset = mediaAssets.findById(assetId).orElseThrow();
        assertThat(asset.status()).as("Asset must transition to DELETED").isEqualTo(MediaAssetStatus.DELETED);
    }

    @Test
    void storageFailureLeavesJobRecoverableAndWorkerRetriesToCompleted() {
        String key = "portfolio/flaky.jpg";
        storage.put(key, new byte[] {0x00, 0x01}, "image/jpeg");
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, key).id();

        storage.setSimulateFailureOnDelete(true);
        deletionService.schedulePieceDeletion(assetId, key);
        UUID jobId = jobRepository.findAll().get(0).getId();

        worker.triggerManualCycle();

        var failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("STORAGE_DELETE_FAILED");
        assertThat(storage.exists(key)).isTrue();

        // Advance time past nextAttemptAt, heal storage, run worker again
        clock.advance(Duration.ofMinutes(1));
        storage.setSimulateFailureOnDelete(false);
        worker.triggerManualCycle();

        var completed = jobRepository.findById(jobId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(storage.exists(key)).isFalse();
        assertThat(mediaAssets.findById(assetId).orElseThrow().status()).isEqualTo(MediaAssetStatus.DELETED);
    }

    @Test
    void claimSpecificJobCannotBypassBackoff() {
        UUID assetId = createAsset(MediaAssetStatus.DELETION_PENDING, "key-backoff").id();
        deletionService.schedulePieceDeletion(assetId, "key-backoff");
        UUID jobId = jobRepository.findAll().get(0).getId();

        Instant start = clock.instant();
        // Initial pending job with next_attempt_at <= now is claimable
        var claim1 = claimer.claimSpecificJob(jobId, start).orElseThrow();
        finalizer.failJob(claim1, new RuntimeException("Simulated error"));

        var failedJob = jobRepository.findById(jobId).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo("FAILED");
        Instant nextAttempt = failedJob.getNextAttemptAt();
        assertThat(nextAttempt).isAfter(start);

        // Before next_attempt_at: claimSpecificJob returns empty
        clock.set(nextAttempt.minusSeconds(1));
        var prematureClaim = claimer.claimSpecificJob(jobId, clock.instant());
        assertThat(prematureClaim).isEmpty();

        // After next_attempt_at: claimSpecificJob successfully claims it
        clock.set(nextAttempt.plusSeconds(1));
        var eligibleClaim = claimer.claimSpecificJob(jobId, clock.instant());
        assertThat(eligibleClaim).isPresent();
        assertThat(eligibleClaim.get().jobId()).isEqualTo(jobId);

        // Once exhausted (attempts == max_attempts), next_attempt_at is null: claimSpecificJob returns empty
        finalizer.failJob(eligibleClaim.get(), new RuntimeException("Fail 2"));
        for (int i = 3; i <= 5; i++) {
            clock.advance(Duration.ofHours(1));
            var c = claimer.claimSpecificJob(jobId, clock.instant()).orElseThrow();
            finalizer.failJob(c, new RuntimeException("Fail " + i));
        }
        var exhausted = jobRepository.findById(jobId).orElseThrow();
        assertThat(exhausted.getAttempts()).isEqualTo(5);
        assertThat(exhausted.getNextAttemptAt()).isNull();

        clock.advance(Duration.ofDays(30));
        var exhaustedClaim = claimer.claimSpecificJob(jobId, clock.instant());
        assertThat(exhaustedClaim).isEmpty();
    }
}
