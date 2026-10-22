package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.domain.RepairPayment;

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

    static void requireCanRead(CurrentActor actor, RepairPayment payment) {
        requireActive(actor);
        if (!actor.hasRole(Role.PLATFORM_ADMIN)
                && !payment.isPayableBy(actor.internalUserId())
                && !payment.fixerUserId().equals(actor.internalUserId())) {
            throw new PaymentAccessDeniedException();
        }
    }

    static void requireCanSimulate(CurrentActor actor, RepairPayment payment) {
        requireActive(actor);
        var owner = actor.hasRole(Role.OWNER) && payment.ownerUserId().equals(actor.internalUserId());
        var manager = actor.hasRole(Role.REAL_ESTATE_MANAGER) && payment.managerUserId() != null
                && payment.managerUserId().equals(actor.internalUserId());
        if (!owner && !manager) {
            throw new PaymentAccessDeniedException();
        }
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new PaymentAccessDeniedException();
        }
    }
}
