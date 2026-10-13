package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.payments.api.ExpenseLedger;
import com.fixup.payments.domain.OwnerCharges;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExpenseLedgerService implements ExpenseLedger {
    private final OwnerCharges charges;

    ExpenseLedgerService(OwnerCharges charges) {
        this.charges = charges;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Expense> approvedExpenses(CurrentActor actor, Instant from, Instant to) {
        PaymentAccess.requireActivePayer(actor);
        var payer = actor.hasRole(Role.PLATFORM_ADMIN) && !actor.hasRole(Role.OWNER)
                && !actor.hasRole(Role.REAL_ESTATE_MANAGER) ? null : actor.internalUserId();
        return charges.findApprovedBetween(payer, from, to).stream()
                .map(charge -> new Expense(charge.id(), charge.propertyId(), charge.requestId(), charge.specialty(),
                        charge.concept(), charge.amount(), charge.fixerUserId(), charge.approvedAt()))
                .toList();
    }
}
