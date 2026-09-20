package com.fixup.fixers;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.FixerVerificationDocumentType;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.domain.FixerProfile;
import com.fixup.fixers.domain.VerificationPolicy;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-UC-16: máquina de estados de la verificación del Fixer, sin contexto de Spring. */
class FixerProfileTest {
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-17T15:00:00Z");

    private CurrentActor actor(UserStatus status, Role... roles) {
        return new CurrentActor(FIXER, "auth0|fixer", Set.of(roles), status);
    }

    @Test
    void newProfileStartsPendingWithoutSubmission() {
        var profile = FixerProfile.pending(FIXER, NOW);
        assertThat(profile.verificationStatus()).isEqualTo(FixerVerificationStatus.PENDING);
        assertThat(profile.submittedAt()).isNull();
        assertThat(profile.isUnderReview()).isFalse();
        assertThat(profile.specialties()).isEmpty();
    }

    @Test
    void profileCanUpdateSpecialties() {
        var profile = FixerProfile.pending(FIXER, NOW);
        var updated = profile.withSpecialties(Set.of(com.fixup.fixers.api.Specialty.PLUMBING, com.fixup.fixers.api.Specialty.ELECTRICAL), NOW);
        assertThat(updated.specialties()).containsExactlyInAnyOrder(com.fixup.fixers.api.Specialty.PLUMBING, com.fixup.fixers.api.Specialty.ELECTRICAL);
    }

    @Test
    void submittingOpensTheReview() {
        var profile = FixerProfile.pending(FIXER, NOW).submitForReview(NOW);
        assertThat(profile.isUnderReview()).isTrue();
        assertThat(profile.submittedAt()).isEqualTo(NOW);
    }

    @Test
    void approvalRecordsTheReviewerAndVerifies() {
        var profile = FixerProfile.pending(FIXER, NOW).submitForReview(NOW).approve(REVIEWER, NOW);
        assertThat(profile.verificationStatus()).isEqualTo(FixerVerificationStatus.VERIFIED);
        assertThat(profile.decidedBy()).isEqualTo(REVIEWER);
        assertThat(profile.rejectionReason()).isNull();
    }

    @Test
    void rejectionKeepsTheReasonAndAllowsAnotherAttempt() {
        var rejected = FixerProfile.pending(FIXER, NOW).submitForReview(NOW)
                .reject(REVIEWER, "Certificado ilegible", NOW);
        assertThat(rejected.verificationStatus()).isEqualTo(FixerVerificationStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Certificado ilegible");
        assertThatCode(() -> rejected.submitForReview(NOW)).doesNotThrowAnyException();
    }

    @Test
    void decidingWithoutSubmissionIsRejected() {
        var profile = FixerProfile.pending(FIXER, NOW);
        assertThatThrownBy(() -> profile.approve(REVIEWER, NOW))
                .isInstanceOf(FixerVerificationConflictException.class)
                .hasMessageContaining("awaiting a decision");
    }

    @Test
    void decidingTwiceIsRejected() {
        var verified = FixerProfile.pending(FIXER, NOW).submitForReview(NOW).approve(REVIEWER, NOW);
        assertThatThrownBy(() -> verified.reject(REVIEWER, "tarde", NOW))
                .isInstanceOf(FixerVerificationConflictException.class);
    }

    @Test
    void verifiedProfileCannotBeSubmittedAgain() {
        var verified = FixerProfile.pending(FIXER, NOW).submitForReview(NOW).approve(REVIEWER, NOW);
        assertThatThrownBy(() -> verified.submitForReview(NOW))
                .isInstanceOf(FixerVerificationConflictException.class)
                .hasMessageContaining("already verified");
    }

    @Test
    void onlyAVerifiedActiveFixerIsEligible() {
        var verified = FixerProfile.pending(FIXER, NOW).submitForReview(NOW).approve(REVIEWER, NOW);
        assertThatCode(() -> verified.requireEligible(actor(UserStatus.ACTIVE, Role.FIXER)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> verified.requireEligible(actor(UserStatus.SUSPENDED, Role.FIXER)))
                .isInstanceOf(FixerNotEligibleException.class);
        assertThatThrownBy(() -> verified.requireEligible(actor(UserStatus.ACTIVE, Role.OWNER)))
                .isInstanceOf(FixerNotEligibleException.class);
    }

    @Test
    void pendingProfileIsNotEligibleToExecuteWork() {
        var pending = FixerProfile.pending(FIXER, NOW).submitForReview(NOW);
        assertThatThrownBy(() -> pending.requireEligible(actor(UserStatus.ACTIVE, Role.FIXER)))
                .isInstanceOf(FixerNotEligibleException.class);
    }

    @Test
    void theReviewNeedsTheMandatoryDocuments() {
        var onlyIdCard = Set.of(FixerVerificationDocumentType.ID_CARD);
        assertThat(VerificationPolicy.isComplete(onlyIdCard)).isFalse();
        assertThat(VerificationPolicy.missing(onlyIdCard))
                .containsExactly(FixerVerificationDocumentType.TRADE_CERTIFICATE);
        assertThat(VerificationPolicy.isComplete(VerificationPolicy.REQUIRED)).isTrue();
        assertThat(VerificationPolicy.missing(VerificationPolicy.REQUIRED)).isEmpty();
    }

    @Test
    void optionalDocumentsDoNotReplaceTheMandatoryOnes() {
        var optionalOnly = Set.of(FixerVerificationDocumentType.INSURANCE,
                FixerVerificationDocumentType.BACKGROUND_CHECK);
        assertThat(VerificationPolicy.isComplete(optionalOnly)).isFalse();
        assertThat(VerificationPolicy.missing(optionalOnly))
                .containsExactlyInAnyOrderElementsOf(VerificationPolicy.REQUIRED);
    }
}
