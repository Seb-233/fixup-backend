package com.fixup.fixers.api;

import com.fixup.identityaccess.api.CurrentActor;
import java.util.Set;
import java.util.UUID;

/** Job and quotation use cases must call this policy to verify eligibility and trade specialties. */
public interface FixerEligibility {
    void requireVerified(CurrentActor actor);

    boolean isVerified(CurrentActor actor);

    /** Persisted trade specialties associated with the fixer. */
    Set<Specialty> specialtiesOf(CurrentActor actor);

    /** Persisted trade specialties associated with the fixer by user ID. */
    Set<Specialty> specialtiesOf(UUID fixerUserId);
}
