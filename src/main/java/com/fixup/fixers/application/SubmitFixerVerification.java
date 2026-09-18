package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.fixers.domain.VerificationDocument;
import com.fixup.fixers.domain.VerificationDocuments;
import com.fixup.fixers.domain.VerificationPolicy;
import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-16: el técnico archiva sus documentos. Puede subirlos por partes; la revisión
 * administrativa se abre sola en cuanto el conjunto obligatorio queda completo.
 */
@Service
public class SubmitFixerVerification {
    private final FixerProfiles profiles;
    private final VerificationDocuments documents;

    SubmitFixerVerification(FixerProfiles profiles, VerificationDocuments documents) {
        this.profiles = profiles;
        this.documents = documents;
    }

    @Transactional
    public void execute(CurrentActor actor, List<DocumentSubmission> submissions) {
        FixerAccess.requireActiveFixer(actor);
        var profile = profiles.findByUserId(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
        if (profile.verificationStatus() == FixerVerificationStatus.VERIFIED) {
            throw new FixerVerificationConflictException("ALREADY_VERIFIED",
                    "The fixer profile is already verified");
        }
        if (profile.verificationStatus() == FixerVerificationStatus.SUSPENDED) {
            throw new FixerVerificationConflictException("PROFILE_SUSPENDED",
                    "A suspended fixer profile cannot be submitted for review");
        }
        var now = Instant.now();
        for (var submission : submissions) {
            documents.save(new VerificationDocument(actor.internalUserId(), submission.type(),
                    submission.storageKey(), now));
        }
        if (VerificationPolicy.isComplete(documents.typesOf(actor.internalUserId()))) {
            profiles.update(profile.submitForReview(now));
        }
    }
}
