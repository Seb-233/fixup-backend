package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;

/** Roles come from PostgreSQL through CurrentActor; never from the token or the frontend. */
final class FixerAccess {
    private FixerAccess() {
    }

    static void requireActiveFixer(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.FIXER)) {
            throw new FixerNotEligibleException();
        }
    }
}
