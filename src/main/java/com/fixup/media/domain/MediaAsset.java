package com.fixup.media.domain;

import com.fixup.media.api.MediaNotFoundException;
import com.fixup.media.api.MediaPurpose;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MediaAsset(
        UUID id,
        UUID ownerUserId,
        MediaPurpose purpose,
        String objectKey,
        String contentType,
        long sizeBytes,
        MediaAssetStatus status,
        Instant uploadExpiresAt,
        Instant confirmedAt,
        Instant createdAt
) {
    public MediaAsset {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(uploadExpiresAt, "uploadExpiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }

    public static MediaAsset createPending(
            UUID id,
            UUID ownerUserId,
            MediaPurpose purpose,
            String objectKey,
            String contentType,
            long sizeBytes,
            Instant uploadExpiresAt,
            Instant now
    ) {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                MediaAssetStatus.PENDING, uploadExpiresAt, null, now);
    }

    public static MediaAsset createPending(
            UUID id,
            UUID ownerUserId,
            String purpose,
            String objectKey,
            String contentType,
            long sizeBytes,
            Instant uploadExpiresAt,
            Instant now
    ) {
        return createPending(id, ownerUserId, MediaPurpose.valueOf(purpose), objectKey, contentType,
                sizeBytes, uploadExpiresAt, now);
    }

    public void requireBelongsTo(UUID candidate) {
        if (!ownerUserId.equals(candidate)) {
            throw new MediaNotFoundException("There is no such media for this user");
        }
    }

    public boolean isExpired(Instant now) {
        return status == MediaAssetStatus.EXPIRED || (status == MediaAssetStatus.PENDING && now.isAfter(uploadExpiresAt));
    }

    public MediaAsset markExpired() {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                MediaAssetStatus.EXPIRED, uploadExpiresAt, confirmedAt, createdAt);
    }

    public MediaAsset markReady(Instant now) {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                MediaAssetStatus.READY, uploadExpiresAt, now, createdAt);
    }

    public MediaAsset markAttached() {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                MediaAssetStatus.ATTACHED, uploadExpiresAt, confirmedAt, createdAt);
    }

    public MediaAsset markInvalid() {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                MediaAssetStatus.INVALID, uploadExpiresAt, confirmedAt, createdAt);
    }

    public MediaAsset markDeletionPending() {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                MediaAssetStatus.DELETION_PENDING, uploadExpiresAt, confirmedAt, createdAt);
    }

    public MediaAsset markDeleted() {
        return new MediaAsset(id, ownerUserId, purpose, objectKey, contentType, sizeBytes,
                MediaAssetStatus.DELETED, uploadExpiresAt, confirmedAt, createdAt);
    }
}
