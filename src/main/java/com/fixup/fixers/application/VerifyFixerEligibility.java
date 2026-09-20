package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.CurrentActor;
import java.util.Set;
import java.util.UUID;
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

    @Override
    @Transactional(readOnly = true)
    public boolean isVerified(CurrentActor actor) {
        try {
            requireVerified(actor);
            return true;
        } catch (FixerNotEligibleException notEligible) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Specialty> specialtiesOf(CurrentActor actor) {
        requireVerified(actor);
        return specialtiesOf(actor.internalUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Specialty> specialtiesOf(UUID fixerUserId) {
        return profiles.findByUserId(fixerUserId)
                .map(profile -> profile.specialties())
                .orElse(Set.of());
    }
}
