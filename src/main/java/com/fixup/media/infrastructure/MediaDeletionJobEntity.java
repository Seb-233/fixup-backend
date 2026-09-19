package com.fixup.media.infrastructure;

import com.fixup.media.domain.MediaDeletionJobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_deletion_jobs")
public class MediaDeletionJobEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "media_asset_id", nullable = false)
    private UUID mediaAssetId;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 32)
    private MediaDeletionJobType jobType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MediaDeletionJobEntity() {
    }

    public MediaDeletionJobEntity(
            UUID id,
            UUID mediaAssetId,
            String objectKey,
            MediaDeletionJobType jobType,
            String status,
            int attempts,
            int maxAttempts,
            UUID claimToken,
            Instant lockedAt,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.mediaAssetId = mediaAssetId;
        this.objectKey = objectKey;
        this.jobType = jobType;
        this.status = status;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.claimToken = claimToken;
        this.lockedAt = lockedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static MediaDeletionJobEntity pending(
            UUID id,
            UUID mediaAssetId,
            String objectKey,
            MediaDeletionJobType jobType,
            int maxAttempts,
            Instant now) {
        return new MediaDeletionJobEntity(
                id,
                mediaAssetId,
                objectKey,
                jobType,
                "PENDING",
                0,
                maxAttempts,
                null,
                null,
                now,
                null,
                now,
                now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMediaAssetId() {
        return mediaAssetId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public MediaDeletionJobType getJobType() {
        return jobType;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
