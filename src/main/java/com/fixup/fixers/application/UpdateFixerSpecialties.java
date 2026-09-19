package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-18 / FR-UC-16: el Fixer configura sus especialidades para recibir solicitudes compatibles.
 */
@Service
public class UpdateFixerSpecialties {
    private final FixerProfiles profiles;

    UpdateFixerSpecialties(FixerProfiles profiles) {
        this.profiles = profiles;
    }

    @Transactional
    public void execute(CurrentActor actor, Collection<Specialty> specialties) {
        FixerAccess.requireActiveFixer(actor);

        if (specialties == null || specialties.isEmpty()) {
            throw new IllegalArgumentException("Specialties must not be empty");
        }
        if (specialties.size() > Specialty.values().length) {
            throw new IllegalArgumentException("Specialties must not exceed allowed count");
        }
        if (specialties.contains(null)) {
            throw new IllegalArgumentException("Specialties cannot contain null elements");
        }
        Set<Specialty> uniqueSpecialties = new HashSet<>(specialties);
        if (uniqueSpecialties.size() != specialties.size()) {
            throw new IllegalArgumentException("Specialties cannot contain duplicates");
        }

        var profile = profiles.findByUserIdForUpdate(actor.internalUserId())
                .orElseThrow(FixerNotEligibleException::new);

        var updated = profile.withSpecialties(Set.copyOf(uniqueSpecialties), Instant.now());
        profiles.update(updated);
    }
}
