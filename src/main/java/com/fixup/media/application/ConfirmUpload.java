package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.MediaInvalidException;
import com.fixup.media.api.MediaNotFoundException;
import com.fixup.media.api.MediaNotReadyException;
import com.fixup.media.api.MediaTypeNotAllowedException;
import com.fixup.media.api.UploadExpiredException;
import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssetStatus;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.MediaContentTypeValidator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmUpload {
    private final MediaAssets mediaAssets;
    private final ObjectStorage objectStorage;
    private final FixerEligibility eligibility;

    public ConfirmUpload(MediaAssets mediaAssets, ObjectStorage objectStorage, FixerEligibility eligibility) {
        this.mediaAssets = mediaAssets;
        this.objectStorage = objectStorage;
        this.eligibility = eligibility;
    }

    @Transactional
    public ConfirmationResponse execute(CurrentActor actor, UUID mediaId) {
        eligibility.requireVerified(actor);

        MediaAsset asset = mediaAssets.findByIdForUpdate(mediaId)
                .orElseThrow(() -> new MediaNotFoundException("There is no such media for this user"));
        asset.requireBelongsTo(actor.internalUserId());

        if (asset.status() == MediaAssetStatus.DELETED) {
            throw new MediaNotFoundException("Media not found");
        }
        if (asset.status() == MediaAssetStatus.INVALID) {
            throw new MediaInvalidException("Media content is invalid");
        }
        if (asset.status() == MediaAssetStatus.READY) {
            return new ConfirmationResponse(asset.id(), MediaAssetStatus.READY.name());
        }
        if (asset.status() == MediaAssetStatus.ATTACHED) {
            return new ConfirmationResponse(asset.id(), asset.status().name());
        }
        if (asset.status() == MediaAssetStatus.EXPIRED) {
            throw new UploadExpiredException("The upload ticket has expired");
        }

        Instant now = Instant.now();
        if (asset.isExpired(now)) {
            mediaAssets.save(asset.markExpired());
            throw new UploadExpiredException("The upload ticket has expired");
        }

        var meta = objectStorage.inspect(asset.objectKey());
        if (!meta.exists() || meta.sizeBytes() <= 0 || meta.sizeBytes() != asset.sizeBytes()) {
            throw new MediaNotReadyException("Object does not exist in storage or size does not match");
        }

        byte[] header = objectStorage.readHead(asset.objectKey(), 32);
        if (!MediaContentTypeValidator.verifyMagicBytes(asset.contentType(), header)) {
            mediaAssets.save(asset.markInvalid());
            try {
                objectStorage.delete(asset.objectKey());
            } catch (Exception ignored) {
            }
            throw new MediaTypeNotAllowedException("Object content signature does not match declared MIME type");
        }

        var confirmed = asset.markReady(now);
        mediaAssets.save(confirmed);

        return new ConfirmationResponse(confirmed.id(), MediaAssetStatus.READY.name());
    }

    public record ConfirmationResponse(UUID mediaId, String status) {
    }
}
