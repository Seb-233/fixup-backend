package com.fixup.remodelings.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.remodelings.api.RemodelingAccessDeniedException;
import com.fixup.remodelings.domain.RemodelingProject;

final class RemodelingAccess {
    private RemodelingAccess() {
    }

    static void requireActiveOwner(CurrentActor actor) {
        requireActive(actor);
        if (!actor.hasRole(Role.OWNER)) {
            throw new RemodelingAccessDeniedException();
        }
    }

    static void requireCanRead(CurrentActor actor, RemodelingProject project) {
        requireActive(actor);
        if (!project.ownerUserId().equals(actor.internalUserId())
                && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new RemodelingAccessDeniedException();
        }
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RemodelingAccessDeniedException();
        }
    }
}
