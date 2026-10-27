package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;

/** Roles come from PostgreSQL through CurrentActor; never from the token or the frontend. */
final class PaymentAccess {
    private PaymentAccess() {
    }

    /** FR-UC-05: paga el propietario del inmueble o el administrador inmobiliario que lo gestiona. */
    static void requireActivePayer(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE
                || (!actor.hasRole(Role.OWNER) && !actor.hasRole(Role.REAL_ESTATE_MANAGER)
                    && !actor.hasRole(Role.PLATFORM_ADMIN))) {
            throw new PaymentAccessDeniedException();
        }
    }

    static void requireActiveFixer(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.FIXER)) {
            throw new PaymentAccessDeniedException();
        }
    }

}
