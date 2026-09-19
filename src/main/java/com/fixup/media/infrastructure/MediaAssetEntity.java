package com.fixup.media.infrastructure;

import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssetStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
class MediaAssetEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "purpose", nullable = false, length = 50)
    private String purpose;

    @Column(name = "object_key", nullable = false, length = 512, unique = true)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MediaAssetStatus status;

    @Column(name = "upload_expires_at", nullable = false)
    private Instant uploadExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MediaAssetEntity() {
    }

    static MediaAssetEntity from(MediaAsset asset) {
        var entity = new MediaAssetEntity();
        entity.id = asset.id();
        entity.ownerUserId = asset.ownerUserId();
        entity.purpose = asset.purpose();
        entity.objectKey = asset.objectKey();
        entity.contentType = asset.contentType();
        entity.sizeBytes = asset.sizeBytes();
        entity.status = asset.status();
        entity.uploadExpiresAt = asset.uploadExpiresAt();
        entity.confirmedAt = asset.confirmedAt();
        entity.createdAt = asset.createdAt();
        return entity;
    }

    void apply(MediaAsset asset) {
        this.status = asset.status();
        this.confirmedAt = asset.confirmedAt();
        this.uploadExpiresAt = asset.uploadExpiresAt();
    }

    MediaAsset toDomain() {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                status, uploadExpiresAt, confirmedAt, createdAt);
    }
}
