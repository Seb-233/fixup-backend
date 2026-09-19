package com.fixup.media.application;

import com.fixup.media.infrastructure.MediaDeletionJobEntity;
import com.fixup.media.infrastructure.MediaDeletionJobJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims and recovers media deletion jobs in short, isolated transactions.
 */
@Component
public class MediaDeletionJobClaimer {
    private static final String SELECT_ELIGIBLE_SQL = """
            SELECT id FROM media_deletion_jobs
            WHERE (status = 'PENDING' OR (status = 'FAILED' AND attempts < max_attempts))
              AND next_attempt_at IS NOT NULL
              AND next_attempt_at <= ?
            ORDER BY next_attempt_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private final MediaDeletionJobJpaRepository repository;
    private final JdbcTemplate jdbc;

    public MediaDeletionJobClaimer(MediaDeletionJobJpaRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStaleJobs(Instant now, Duration lockTimeout) {
        Instant cutoff = now.minus(lockTimeout);
        return repository.recoverStaleJobs(cutoff, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedDeletionJob> claimSpecificJob(UUID jobId, Instant now) {
        UUID claimToken = UUID.randomUUID();
        int updated = repository.tryClaimSpecificJob(jobId, claimToken, now);
        if (updated == 1) {
            return repository.findById(jobId).map(entity -> toClaimed(entity, claimToken));
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedDeletionJob> claimNextBatch(Instant now, int batchSize) {
        List<UUID> eligibleIds = jdbc.query(
                SELECT_ELIGIBLE_SQL,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                java.sql.Timestamp.from(now),
                batchSize
        );
        List<ClaimedDeletionJob> claimed = new ArrayList<>();
        for (UUID id : eligibleIds) {
            UUID claimToken = UUID.randomUUID();
            int updated = repository.tryClaimSpecificJob(id, claimToken, now);
            if (updated == 1) {
                repository.findById(id).ifPresent(entity -> claimed.add(toClaimed(entity, claimToken)));
            }
        }
        return claimed;
    }

    private ClaimedDeletionJob toClaimed(MediaDeletionJobEntity entity, UUID claimToken) {
        return new ClaimedDeletionJob(
                entity.getId(),
                claimToken,
                entity.getJobType(),
                entity.getMediaAssetId(),
                entity.getObjectKey(),
                entity.getAttempts(),
                entity.getMaxAttempts()
        );
    }
}
