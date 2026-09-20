package com.fixup.jobs.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.jobs.api.JobAccessDeniedException;

/** Roles come from PostgreSQL through CurrentActor; never from the token or the frontend. */
final class JobAccess {
    private JobAccess() {
    }

    static void requireActiveFixer(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.FIXER)) {
            throw new JobAccessDeniedException();
        }
    }
}
