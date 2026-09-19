package com.fixup.contracts.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.domain.LeaseContract;

final class ContractAccess {
    private ContractAccess() {
    }

    static void requireActiveUser(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new ContractAccessDeniedException();
        }
    }

    static void requireOwnerOrManager(CurrentActor actor) {
        if (!actor.hasRole(Role.OWNER) && !actor.hasRole(Role.REAL_ESTATE_MANAGER)
                && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException();
        }
    }

    static void requireTenantOrOwner(CurrentActor actor) {
        if (!actor.hasRole(Role.OWNER) && !actor.hasRole(Role.TENANT)
                && !actor.hasRole(Role.REAL_ESTATE_MANAGER)
                && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException();
        }
    }

    static void requireVisibleBy(CurrentActor actor, LeaseContract contract) {
        contract.requireVisibleBy(actor.internalUserId());
    }
}
