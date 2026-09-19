package com.fixup.media.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.media.api.MediaException;
import com.fixup.media.api.MediaTooLargeException;
import com.fixup.media.api.MediaTypeNotAllowedException;
import com.fixup.media.domain.MediaAsset;
import com.fixup.media.domain.MediaAssets;
import com.fixup.media.domain.MediaContentTypeValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestUploadTicket {
    private static final Duration UPLOAD_EXPIRATION = Duration.ofMinutes(15);
    private static final String ALLOWED_PURPOSE = "FIXER_PORTFOLIO";

    private final MediaAssets mediaAssets;
    private final ObjectStorage objectStorage;
    private final FixerEligibility eligibility;
    private final long maxFileSize;

    public RequestUploadTicket(
            MediaAssets mediaAssets,
            ObjectStorage objectStorage,
            FixerEligibility eligibility,
            @Value("${fixup.media.portfolio.max-file-size:10485760}") long maxFileSize) {
        this.mediaAssets = mediaAssets;
        this.objectStorage = objectStorage;
        this.eligibility = eligibility;
        this.maxFileSize = maxFileSize;
    }

    @Transactional
    public UploadTicketResponse execute(CurrentActor actor, NewUploadRequest request) {
        eligibility.requireVerified(actor);

        if (!ALLOWED_PURPOSE.equals(request.purpose())) {
            throw new MediaException(400, "INVALID_PURPOSE", "Purpose must be FIXER_PORTFOLIO");
        }
        if (!MediaContentTypeValidator.isAllowed(request.contentType())) {
            throw new MediaTypeNotAllowedException("Only JPEG, PNG and WebP images are allowed");
        }
        if (request.sizeBytes() <= 0) {
            throw new MediaException(400, "INVALID_SIZE", "File size must be greater than zero");
        }
        if (request.sizeBytes() > maxFileSize) {
            throw new MediaTooLargeException("File size exceeds the allowed limit of " + maxFileSize + " bytes");
        }

        UUID mediaId = UUID.randomUUID();
        String extension = MediaContentTypeValidator.extensionFor(request.contentType());
        String objectKey = "portfolio/" + actor.internalUserId() + "/" + mediaId + "." + extension;

        var ticket = objectStorage.createUploadTicket(objectKey, request.contentType(), request.sizeBytes(), UPLOAD_EXPIRATION);
        Instant now = Instant.now();

        var asset = MediaAsset.createPending(
                mediaId,
                actor.internalUserId(),
                request.purpose(),
                objectKey,
                request.contentType(),
                request.sizeBytes(),
                ticket.expiresAt(),
                now);
        mediaAssets.save(asset);

        return new UploadTicketResponse(
                mediaId,
                ticket.method(),
                ticket.uploadUrl(),
                ticket.headers(),
                ticket.expiresAt());
    }

    public record NewUploadRequest(String purpose, String contentType, long sizeBytes) {
    }

    public record UploadTicketResponse(
            UUID mediaId,
            String method,
            String uploadUrl,
            Map<String, String> headers,
            Instant expiresAt) {
    }
}
