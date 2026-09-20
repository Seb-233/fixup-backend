package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.api.Specialty;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** FR-UC-23: what a PLATFORM_ADMIN needs to decide a verification -- the documents themselves. */
public record FixerVerificationReviewView(UUID fixerUserId, FixerVerificationStatus status, boolean underReview,
        Instant submittedAt, Instant decidedAt, UUID decidedBy, String rejectionReason,
        Set<Specialty> specialties, List<SubmittedDocumentView> documents) {

    public record SubmittedDocumentView(FixerVerificationDocumentType type, UUID mediaId, String readUrl,
            Instant readUrlExpiresAt) {
    }
}
