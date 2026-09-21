package com.fixup.quotations.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.quotations.api.QuotationAccessDeniedException;

/** Roles come from PostgreSQL through CurrentActor; never from the token or the frontend. */
final class QuotationAccess {
    private QuotationAccess() {
    }

    static void requireActiveFixer(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.FIXER)) {
            throw new QuotationAccessDeniedException();
        }
    }

    static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new QuotationAccessDeniedException();
        }
    }
}
