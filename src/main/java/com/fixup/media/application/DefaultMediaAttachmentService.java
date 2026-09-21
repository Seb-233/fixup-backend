package com.fixup.media.application;

import com.fixup.media.api.MediaAlreadyAttachedException;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.media.api.MediaException;
import com.fixup.media.api.MediaNotFoundException;
import com.fixup.media.api.MediaNotReadyException;
import com.fixup.media.api.MediaPurpose;
import com.fixup.media.api.SignedMediaView;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultMediaAttachmentService implements MediaAttachmentService {
    private static final Duration READ_EXPIRATION = Duration.ofMinutes(15);
    private static final int MAX_PHOTOS = 6;
    private static final int MAX_VERIFICATION_DOCUMENTS = 4;

    private final MediaAssets mediaAssets;
    private final ObjectStorage objectStorage;

    DefaultMediaAttachmentService(MediaAssets mediaAssets, ObjectStorage objectStorage) {
        this.mediaAssets = mediaAssets;
        this.objectStorage = objectStorage;
    }

    @Override
    @Transactional
    public void attachRepairRequestPhotos(UUID ownerUserId, List<UUID> mediaIds) {
        attach(ownerUserId, mediaIds, MediaPurpose.REPAIR_REQUEST, MAX_PHOTOS);
    }

    @Override
    @Transactional
    public void attachFixerVerificationDocuments(UUID ownerUserId, List<UUID> mediaIds) {
        attach(ownerUserId, mediaIds, MediaPurpose.FIXER_VERIFICATION, MAX_VERIFICATION_DOCUMENTS);
    }

    private void attach(UUID ownerUserId, List<UUID> mediaIds, MediaPurpose expectedPurpose, int maxCount) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return;
        }
        if (mediaIds.size() > maxCount) {
            throw new IllegalArgumentException("Cannot attach more than " + maxCount + " media assets");
        }
        if (mediaIds.contains(null)) {
            throw new IllegalArgumentException("mediaIds cannot contain null elements");
        }
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new IllegalArgumentException("Duplicate media IDs are not allowed");
        }

        for (UUID mediaId : mediaIds) {
            var asset = mediaAssets.findByIdForUpdate(mediaId)
                    .orElseThrow(() -> new MediaNotFoundException("There is no such media for this user"));

            if (!asset.ownerUserId().equals(ownerUserId)) {
                throw new MediaNotFoundException("There is no such media for this user");
            }
            if (asset.purpose() != expectedPurpose) {
                throw new MediaException(400, "INVALID_PURPOSE", "Media purpose must be " + expectedPurpose);
            }
            if (asset.status() == MediaAssetStatus.ATTACHED) {
                throw new MediaAlreadyAttachedException("Media is already attached");
            }
            if (asset.status() != MediaAssetStatus.READY) {
                throw new MediaNotReadyException("Media is not ready for attachment");
            }

            mediaAssets.save(asset.markAttached());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignedMediaView> resolveReadUrls(List<UUID> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return List.of();
        }
        List<SignedMediaView> result = new ArrayList<>(mediaIds.size());
        for (UUID mediaId : mediaIds) {
            var assetOpt = mediaAssets.findById(mediaId);
            if (assetOpt.isPresent()) {
                var asset = assetOpt.get();
                var ticket = objectStorage.createReadTicket(asset.objectKey(), READ_EXPIRATION);
                result.add(new SignedMediaView(mediaId, ticket.readUrl(), ticket.expiresAt()));
            }
        }
        return List.copyOf(result);
    }
}
