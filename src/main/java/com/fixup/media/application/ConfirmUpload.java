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
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmUpload {
    private final MediaAssets mediaAssets;
    private final ObjectStorage objectStorage;
    private final FixerEligibility eligibility;
    private final MediaDeletionService deletionService;
    private final Clock clock;

    public ConfirmUpload(
            MediaAssets mediaAssets,
            ObjectStorage objectStorage,
            FixerEligibility eligibility,
            MediaDeletionService deletionService,
            Clock clock) {
        this.mediaAssets = mediaAssets;
        this.objectStorage = objectStorage;
        this.eligibility = eligibility;
        this.deletionService = deletionService;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = {MediaTypeNotAllowedException.class, UploadExpiredException.class})
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

        Instant now = Instant.now(clock);
        if (asset.isExpired(now)) {
            mediaAssets.save(asset.markExpired());
            throw new UploadExpiredException("The upload ticket has expired");
        }

        var meta = objectStorage.inspect(asset.objectKey());
        if (!meta.exists() || meta.sizeBytes() <= 0 || meta.sizeBytes() != asset.sizeBytes()) {
            throw new MediaNotReadyException("Object does not exist in storage or size does not match");
        }

        String normalizedMeta = normalizeContentType(meta.contentType());
        String normalizedDeclared = normalizeContentType(asset.contentType());
        if (normalizedMeta.isEmpty()
                || !MediaContentTypeValidator.isAllowed(normalizedMeta)
                || !normalizedMeta.equals(normalizedDeclared)) {
            throw new MediaNotReadyException("Stored content-type does not match declared MIME type");
        }

        byte[] header = objectStorage.readHead(asset.objectKey(), 32);
        if (!MediaContentTypeValidator.verifyMagicBytes(asset.contentType(), header)) {
            mediaAssets.save(asset.markInvalid());
            deletionService.scheduleInvalidObjectPurge(asset.id(), asset.objectKey());
            throw new MediaTypeNotAllowedException("Object content signature does not match declared MIME type");
        }

        var confirmed = asset.markReady(now);
        mediaAssets.save(confirmed);

        return new ConfirmationResponse(confirmed.id(), MediaAssetStatus.READY.name());
    }

    private static String normalizeContentType(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim().toLowerCase();
        int semicolon = cleaned.indexOf(';');
        if (semicolon != -1) {
            cleaned = cleaned.substring(0, semicolon).trim();
        }
        return cleaned;
    }

    public record ConfirmationResponse(UUID mediaId, String status) {
    }
}
