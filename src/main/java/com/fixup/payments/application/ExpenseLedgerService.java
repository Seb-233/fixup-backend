package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.ExpenseLedger;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.PaidExpenses;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.identityaccess.api.Role;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExpenseLedgerService implements ExpenseLedger {
    private final PaidExpenses expenses;
    private final PropertyDirectory properties;
    ExpenseLedgerService(PaidExpenses expenses, PropertyDirectory properties) {
        this.expenses = expenses;
        this.properties = properties;
    }
    @Override
    @Transactional(readOnly = true)
    public List<Expense> approvedExpenses(CurrentActor actor, Instant from, Instant to) {
        PaymentAccess.requireActivePayer(actor);
        if (from == null || to == null || !from.isBefore(to)) {
            throw new PaymentConflictException("INVALID_EXPENSE_PERIOD", "A nonempty half-open period is required");
        }
        var rows = expenses.between(from, to);
        var propertyIds = rows.stream().map(r -> r.expense().propertyId()).filter(java.util.Objects::nonNull).distinct().toList();
        var current = properties.findAll(propertyIds).stream().collect(java.util.stream.Collectors.toMap(p -> p.id(), p -> p));
        return rows.stream().filter(r -> {
            if (actor.hasRole(Role.PLATFORM_ADMIN)) return true;
            if (actor.hasRole(Role.OWNER) && r.ownerUserId().equals(actor.internalUserId())) return true;
            var p = current.get(r.expense().propertyId());
            return actor.hasRole(Role.REAL_ESTATE_MANAGER) && p != null
                    && p.ownerUserId().equals(r.ownerUserId()) && p.isManagedBy(actor.internalUserId());
        }).map(r -> {
            var e = r.expense();
            if (e.propertyId() == null || e.specialty() == null || e.specialty().isBlank()
                    || e.concept() == null || e.concept().isBlank()) {
                throw new PaymentConflictException("EXPENSE_CONTEXT_INCOMPLETE",
                        "Historical paid expense " + e.chargeId() + " requires context reconciliation");
            }
            return e;
        }).toList();
    }
}
