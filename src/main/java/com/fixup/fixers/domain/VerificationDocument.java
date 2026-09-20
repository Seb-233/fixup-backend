package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * References a media asset already validated by the media module (owned by this fixer, purpose
 * FIXER_VERIFICATION, status READY): no document content or storage key crosses this API.
 */
public record VerificationDocument(UUID userId, FixerVerificationDocumentType type, UUID mediaId,
        Instant submittedAt) {
    public VerificationDocument {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(mediaId);
        Objects.requireNonNull(submittedAt);
    }
}
