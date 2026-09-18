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
    private static final java.util.Map<java.util.UUID, java.util.Set<String>> OVERRIDDEN_SPECIALTIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    VerifyFixerEligibility(FixerProfiles profiles) {
        this.profiles = profiles;
    }

    @Override
    public void assignSpecialties(java.util.UUID userId, java.util.Set<String> specialties) {
        if (specialties == null) {
            OVERRIDDEN_SPECIALTIES.remove(userId);
        } else {
            OVERRIDDEN_SPECIALTIES.put(userId, specialties);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void requireVerified(CurrentActor actor) {
        var profile = profiles.findByUserId(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
        profile.requireEligible(actor);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> specialtiesOf(CurrentActor actor) {
        var profile = profiles.findByUserId(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
        profile.requireEligible(actor);
        var overridden = OVERRIDDEN_SPECIALTIES.get(actor.internalUserId());
        if (overridden != null) {
            return overridden;
        }
        return profile.specialties();
    }
}
