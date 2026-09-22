package com.fixup.requests.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import java.util.Set;

/** Roles come from PostgreSQL through CurrentActor; never from the token or the frontend. */
final class RequestAccess {
    private static final Set<Role> REQUESTERS =
            Set.of(Role.OWNER, Role.TENANT, Role.REAL_ESTATE_MANAGER);

    private RequestAccess() {
    }

    /** Whoever holds a property relationship may open a request against it. */
    static void requireActiveOwner(CurrentActor actor) {
        requireActive(actor);
        if (!actor.hasRole(Role.OWNER)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    static void requireActiveRequester(CurrentActor actor) {
        requireActive(actor);
        if (REQUESTERS.stream().noneMatch(actor::hasRole)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    static void requireActiveFixer(CurrentActor actor) {
        requireActive(actor);
        if (!actor.hasRole(Role.FIXER)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RepairRequestAccessDeniedException();
        }
    }
}
