package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerDirectory;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-16: el técnico se presenta con su ciudad, una biografía corta y sus años de experiencia.
 * La ciudad es la que usa la bandeja de solicitudes para mostrarle trabajos cercanos.
 */
@Service
public class UpdateFixerPresentation {
    private final FixerProfiles profiles;
    private final FixerDirectory directory;

    UpdateFixerPresentation(FixerProfiles profiles, FixerDirectory directory) {
        this.profiles = profiles;
        this.directory = directory;
    }

    @Transactional
    public FixerDirectory.FixerListing execute(CurrentActor actor, String city, String bio, Integer yearsExperience) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.FIXER)) {
            throw new FixerNotEligibleException();
        }
        if (yearsExperience != null && (yearsExperience < 0 || yearsExperience > 70)) {
            throw new IllegalArgumentException("yearsExperience must be between 0 and 70");
        }
        profiles.updatePresentation(actor.internalUserId(), blankToNull(city, 100), blankToNull(bio, 600),
                yearsExperience, Instant.now());
        return directory.find(actor.internalUserId()).orElseThrow(FixerNotEligibleException::new);
    }

    private static String blankToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("Text must be at most " + max + " characters");
        }
        return trimmed;
    }
}
