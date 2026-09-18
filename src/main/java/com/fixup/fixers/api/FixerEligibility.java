package com.fixup.fixers.api;

import com.fixup.identityaccess.api.CurrentActor;
import java.util.Set;

/** Job use cases must call this policy before allowing a fixer to execute work. */
public interface FixerEligibility {
    void requireVerified(CurrentActor actor);

    /**
     * Trade specialties associated with the fixer.
     * Defined temporarily as all standard specialties for an active, verified fixer
     * as FixerProfile does not model individual specialties in PostgreSQL yet.
     */
    Set<String> specialtiesOf(CurrentActor actor);

    /** Optional assignment of specialties for a fixer (useful for testing or profile updates). */
    default void assignSpecialties(java.util.UUID userId, Set<String> specialties) {}
}
