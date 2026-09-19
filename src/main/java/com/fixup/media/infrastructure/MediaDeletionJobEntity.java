package com.fixup.media.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MediaDeletionJobEntity() {
    }

    public MediaDeletionJobEntity(UUID id, UUID mediaAssetId, String objectKey, String status,
            int attempts, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.mediaAssetId = mediaAssetId;
        this.objectKey = objectKey;
        this.status = status;
        this.attempts = attempts;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void markCompleted(Instant now) {
        this.status = "COMPLETED";
        this.updatedAt = now;
    }

    public void markFailed(Instant now) {
        this.status = "FAILED";
        this.attempts += 1;
        this.updatedAt = now;
    }
}
