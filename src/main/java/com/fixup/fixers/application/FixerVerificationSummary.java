package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import com.fixup.fixers.api.FixerVerificationStatus;
import java.time.Instant;
import java.util.Set;

/** Read model of the fixer's own verification. It never exposes the reviewer or the storage keys. */
public record FixerVerificationSummary(FixerVerificationStatus status, boolean underReview, Instant submittedAt,
        Instant decidedAt, String rejectionReason, Set<FixerVerificationDocumentType> submittedDocuments,
        Set<FixerVerificationDocumentType> missingDocuments) {
}
