package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The backend stores only the object key. No document content crosses the API.
 */
public record VerificationDocument(UUID userId, FixerVerificationDocumentType type, String storageKey,
        Instant submittedAt) {
    public VerificationDocument {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(submittedAt);
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("A verification document requires a storage key");
        }
        storageKey = storageKey.trim();
    }
}
