package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.fixers.domain.VerificationDocuments;
import com.fixup.fixers.domain.VerificationPolicy;
import com.fixup.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetFixerVerification {
    private final FixerProfiles profiles;
    private final VerificationDocuments documents;

    GetFixerVerification(FixerProfiles profiles, VerificationDocuments documents) {
        this.profiles = profiles;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public FixerVerificationSummary execute(CurrentActor actor) {
        FixerAccess.requireActiveFixer(actor);
        var profile = profiles.findByUserId(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
        var submitted = documents.typesOf(actor.internalUserId());
        return new FixerVerificationSummary(profile.verificationStatus(), profile.isUnderReview(),
                profile.submittedAt(), profile.decidedAt(), profile.rejectionReason(), submitted,
                VerificationPolicy.missing(submitted), profile.specialties());
    }
}
