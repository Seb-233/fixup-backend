package com.fixup.fixers.api;

import com.fixup.identityaccess.api.CurrentActor;

/** Job use cases must call this policy before allowing a fixer to execute work. */
public interface FixerEligibility {
    void requireVerified(CurrentActor actor);
}
