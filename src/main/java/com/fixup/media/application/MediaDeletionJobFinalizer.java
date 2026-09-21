package com.fixup.media.application;

import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.MediaDeletionJobType;
import com.fixup.media.infrastructure.MediaDeletionJobJpaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finalizes media deletion jobs (complete or fail) in independent transactions.
 */
@Component
public class MediaDeletionJobFinalizer {
    private static final Logger log = LoggerFactory.getLogger(MediaDeletionJobFinalizer.class);

    private final MediaDeletionJobJpaRepository repository;
    private final MediaAssets mediaAssets;
    private final Clock clock;
    private final Duration initialBackoff;

    public MediaDeletionJobFinalizer(
            MediaDeletionJobJpaRepository repository,
            MediaAssets mediaAssets,
            Clock clock,
            @Value("${fixup.media.deletion.initial-backoff:30s}") Duration initialBackoff) {
        this.repository = repository;
        this.mediaAssets = mediaAssets;
        this.clock = clock;
        this.initialBackoff = initialBackoff;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeJob(ClaimedDeletionJob job) {
        Instant now = Instant.now(clock);
        int updated = repository.completeClaimedJob(job.jobId(), job.claimToken(), now);
        if (updated == 1) {
            if (job.jobType() == MediaDeletionJobType.PIECE_DELETION) {
                mediaAssets.findById(job.mediaAssetId()).ifPresent(asset -> {
                    mediaAssets.save(asset.markDeleted());
                });
            }
            log.info("Media deletion job {} completed successfully", job.jobId());
            return true;
        }
        log.warn("Job {} could not be marked COMPLETED; claim token or status mismatch", job.jobId());
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failJob(ClaimedDeletionJob job, Exception exception) {
        Instant now = Instant.now(clock);
        int newAttempts = job.currentAttempts() + 1;
        String sanitizedError = sanitizeError(exception);
        Instant nextAttemptAt = null;

        if (newAttempts < job.maxAttempts()) {
            long multiplier = 1L << Math.min(newAttempts - 1, 10);
            Duration backoff = initialBackoff.multipliedBy(multiplier);
            nextAttemptAt = now.plus(backoff);
        }

        int updated = repository.failClaimedJob(
                job.jobId(),
                job.claimToken(),
                newAttempts,
                nextAttemptAt,
                sanitizedError,
                now
        );

        if (updated == 1) {
            log.warn("Media deletion job {} marked FAILED (attempt {}/{}, nextAttemptAt={})",
                    job.jobId(), newAttempts, job.maxAttempts(), nextAttemptAt);
            return true;
        }
        log.warn("Job {} could not be marked FAILED; claim token or status mismatch", job.jobId());
        return false;
    }

    public static String sanitizeError(Exception ex) {
        if (ex == null) {
            return "STORAGE_DELETE_FAILED";
        }
        String name = ex.getClass().getSimpleName().toLowerCase();
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (name.contains("timeout") || msg.contains("timeout") || msg.contains("timed out")) {
            return "STORAGE_TIMEOUT";
        }
        if (name.contains("unavailable") || msg.contains("unavailable")
                || msg.contains("connection refused") || msg.contains("connect")) {
            return "STORAGE_UNAVAILABLE";
        }
        return "STORAGE_DELETE_FAILED";
    }
}
