package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record FixerProfile(UUID userId, FixerVerificationStatus verificationStatus, Set<Specialty> specialties,
        Instant submittedAt, Instant decidedAt, UUID decidedBy, String rejectionReason,
        Instant consentAcceptedAt, String consentVersion,
        Instant createdAt, Instant updatedAt) {

    public FixerProfile {
        specialties = specialties == null ? Set.of() : Set.copyOf(specialties);
    }

    /** A profile is created the moment identityaccess grants the FIXER role, before any document exists. */
    public static FixerProfile pending(UUID userId, Instant now) {
        return new FixerProfile(userId, FixerVerificationStatus.PENDING, Set.of(), null, null, null, null,
                null, null, now, now);
    }

    public void requireEligible(CurrentActor actor) {
        if (!userId.equals(actor.internalUserId()) || actor.status() != UserStatus.ACTIVE
                || !actor.hasRole(Role.FIXER) || verificationStatus != FixerVerificationStatus.VERIFIED) {
            throw new FixerNotEligibleException();
        }
    }

    public FixerProfile withSpecialties(Set<Specialty> newSpecialties, Instant now) {
        return new FixerProfile(userId, verificationStatus, newSpecialties, submittedAt, decidedAt, decidedBy,
                rejectionReason, consentAcceptedAt, consentVersion, createdAt, now);
    }

    /**
     * Records the fixer's explicit consent to personal-data processing. Idempotent: if consent has
     * already been recorded, the existing timestamp and version are preserved unchanged -- consent
     * is a one-way door and the historical record must not be overwritten.
     *
     * @param version the version string of the terms the fixer is accepting (e.g. "v1.0").
     * @param now     the instant at which the fixer expressed consent.
     * @return a new profile with consent recorded (or {@code this} if consent was already on file).
     */
    public FixerProfile recordConsent(String version, Instant now) {
        if (consentAcceptedAt != null) {
            // Already consented: preserve the historical record. Idempotent.
            return this;
        }
        if (version == null || version.isBlank()) {
            throw new FixerVerificationConflictException("INVALID_CONSENT_VERSION",
                    "A non-blank consent version is required");
        }
        return new FixerProfile(userId, verificationStatus, specialties, submittedAt, decidedAt, decidedBy,
                rejectionReason, now, version, createdAt, now);
    }

    /** Whether the fixer has given consent to personal-data processing (required before review). */
    public boolean hasConsent() {
        return consentAcceptedAt != null;
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
        return new FixerProfile(userId, FixerVerificationStatus.PENDING, specialties, now, null, null, null,
                consentAcceptedAt, consentVersion, createdAt, now);
    }

    public FixerProfile approve(UUID reviewer, Instant now) {
        requireUnderReview();
        return new FixerProfile(userId, FixerVerificationStatus.VERIFIED, specialties, submittedAt, now, reviewer, null,
                consentAcceptedAt, consentVersion, createdAt, now);
    }

    public FixerProfile reject(UUID reviewer, String reason, Instant now) {
        requireUnderReview();
        return new FixerProfile(userId, FixerVerificationStatus.REJECTED, specialties, submittedAt, now, reviewer, reason,
                consentAcceptedAt, consentVersion, createdAt, now);
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