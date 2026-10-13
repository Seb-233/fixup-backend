package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.OwnerCharge;
import com.fixup.payments.domain.OwnerCharges;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-05: los cobros que el propietario o su administrador tienen por pagar o ya pagaron. */
@Service
public class ListOwnerCharges {
    private final OwnerCharges charges;

    ListOwnerCharges(OwnerCharges charges) {
        this.charges = charges;
    }

    @Transactional(readOnly = true)
    public List<OwnerCharge> mine(CurrentActor actor) {
        PaymentAccess.requireActivePayer(actor);
        return charges.findByPayer(actor.internalUserId());
    }

    /** El cobro de una solicitud lo ven quien paga, el técnico que lo recibe y el admin de plataforma. */
    @Transactional(readOnly = true)
    public OwnerCharge forRequest(CurrentActor actor, UUID requestId) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new PaymentAccessDeniedException();
        }
        var charge = charges.findByRequest(requestId)
                .orElseThrow(() -> new PaymentConflictException("CHARGE_NOT_FOUND", "The request has no charge yet"));
        var userId = actor.internalUserId();
        if (!charge.isPayableBy(userId) && !charge.fixerUserId().equals(userId) && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new PaymentAccessDeniedException();
        }
        return charge;
    }
}
