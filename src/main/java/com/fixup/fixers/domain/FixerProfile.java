package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record FixerProfile(UUID userId, FixerVerificationStatus verificationStatus, Instant submittedAt,
        Instant decidedAt, UUID decidedBy, String rejectionReason, Instant createdAt, Instant updatedAt) {

    /** A profile is created the moment identityaccess grants the FIXER role, before any document exists. */
    public static FixerProfile pending(UUID userId, Instant now) {
        return new FixerProfile(userId, FixerVerificationStatus.PENDING, null, null, null, null, now, now);
    }

    public void requireEligible(CurrentActor actor) {
        if (!userId.equals(actor.internalUserId()) || actor.status() != UserStatus.ACTIVE
                || !actor.hasRole(Role.FIXER) || verificationStatus != FixerVerificationStatus.VERIFIED) {
            throw new FixerNotEligibleException();
        }
    }

    /**
     * Fixer specialties are temporarily defined as all standard trades
     * as FixerProfile does not model individual specialties in PostgreSQL yet.
     */
    public Set<String> specialties() {
        return Set.of("PLUMBING", "ELECTRICAL", "PAINTING", "CARPENTRY", "MASONRY", "GENERAL");
    }

    /** The fixer sends a complete set of documents. A rejected profile may try again. */
    public FixerProfile submitForReview(Instant now) {
        if (verificationStatus == FixerVerificationStatus.VERIFIED) {
            throw new FixerVerificationConflictException("ALREADY_VERIFIED",
                    "The fixer profile is already verified");
        }
        if (verificationStatus == FixerVerificationStatus.SUSPENDED) {
            throw new FixerVerificationConflictException("PROFILE_SUSPENDED",
                    "A suspended fixer profile cannot be submitted for review");
        }
        return new FixerProfile(userId, FixerVerificationStatus.PENDING, now, null, null, null, createdAt, now);
    }

    public FixerProfile approve(UUID reviewer, Instant now) {
        requireUnderReview();
        return new FixerProfile(userId, FixerVerificationStatus.VERIFIED, submittedAt, now, reviewer, null,
                createdAt, now);
    }

    public FixerProfile reject(UUID reviewer, String reason, Instant now) {
        requireUnderReview();
        return new FixerProfile(userId, FixerVerificationStatus.REJECTED, submittedAt, now, reviewer, reason,
                createdAt, now);
    }

    public boolean isUnderReview() {
        return verificationStatus == FixerVerificationStatus.PENDING && submittedAt != null;
    }

    private void requireUnderReview() {
        if (!isUnderReview()) {
            throw new FixerVerificationConflictException("NOT_UNDER_REVIEW",
                    "The fixer has no verification submission awaiting a decision");
        }
    }
}
