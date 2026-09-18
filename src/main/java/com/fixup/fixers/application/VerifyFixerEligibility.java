package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class VerifyFixerEligibility implements FixerEligibility {
    private final FixerProfiles profiles;

    VerifyFixerEligibility(FixerProfiles profiles) {
        this.profiles = profiles;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireVerified(CurrentActor actor) {
        var profile = profiles.findByUserId(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
        profile.requireEligible(actor);
    }
}
