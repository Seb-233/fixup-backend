package com.fixup.identityaccess.domain;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.util.Set;

public final class RolePolicy {
    private static final Set<Role> SELF_ASSIGNABLE = Set.of(Role.OWNER, Role.TENANT, Role.FIXER);

    private RolePolicy() {
    }

    public static void requireSelfAssignable(Role role) {
        if (role == null || !SELF_ASSIGNABLE.contains(role)) {
            throw new IdentityProblem(IdentityProblem.Reason.ACCESS_DENIED);
        }
    }

    public static void requireAdministrator(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new IdentityProblem(IdentityProblem.Reason.ACCESS_DENIED);
        }
    }
}
