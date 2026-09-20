package com.fixup.media.infrastructure;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaDeletionJobJpaRepository extends JpaRepository<MediaDeletionJobEntity, UUID> {

    @Modifying
    @Query(value = """
        UPDATE media_deletion_jobs
        SET status = 'PROCESSING',
            claim_token = :claimToken,
            locked_at = :now,
            updated_at = :now
        WHERE id = :id
          AND (status = 'PENDING' OR (status = 'FAILED' AND attempts < max_attempts))
          AND next_attempt_at IS NOT NULL
          AND next_attempt_at <= :now
        """, nativeQuery = true)
    int tryClaimSpecificJob(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now);

    @Modifying
    @Query(value = """
        UPDATE media_deletion_jobs
        SET status = 'PENDING',
            claim_token = NULL,
            locked_at = NULL,
            updated_at = :now
        WHERE status = 'PROCESSING'
          AND locked_at < :cutoff
        """, nativeQuery = true)
    int recoverStaleJobs(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Modifying
    @Query(value = """
        UPDATE media_deletion_jobs
        SET status = 'COMPLETED',
            claim_token = NULL,
            locked_at = NULL,
            updated_at = :now
        WHERE id = :id
          AND status = 'PROCESSING'
          AND claim_token = :claimToken
        """, nativeQuery = true)
    int completeClaimedJob(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now);

    @Modifying
    @Query(value = """
        UPDATE media_deletion_jobs
        SET status = 'FAILED',
            attempts = :newAttempts,
            next_attempt_at = :nextAttemptAt,
            claim_token = NULL,
            locked_at = NULL,
            last_error = :lastError,
            updated_at = :now
        WHERE id = :id
          AND status = 'PROCESSING'
          AND claim_token = :claimToken
        """, nativeQuery = true)
    int failClaimedJob(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("newAttempts") int newAttempts,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("now") Instant now);
}
