package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.payments.api.ExpenseLedger;
import com.fixup.payments.domain.CashSettlements;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.RepairPayments;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExpenseLedgerService implements ExpenseLedger {
    private final RepairPayments payments;
    private final CashSettlements settlements;

    ExpenseLedgerService(RepairPayments payments, CashSettlements settlements) {
        this.payments = payments;
        this.settlements = settlements;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Expense> approvedExpenses(CurrentActor actor, Instant from, Instant to) {
        PaymentAccess.requireActivePayer(actor);
        var payer = actor.hasRole(Role.PLATFORM_ADMIN) && !actor.hasRole(Role.OWNER)
                && !actor.hasRole(Role.REAL_ESTATE_MANAGER) ? null : actor.internalUserId();
        var expenses = new ArrayList<Expense>();
        payments.findApprovedBetween(payer, from, to).forEach(payment -> expenses.add(new Expense(payment.id(),
                payment.requestId(),
                payment.purpose() == PaymentPurpose.SERVICE ? Kind.TRANSFER : Kind.OUTSTANDING_BALANCE,
                payment.amount(), payment.fixerUserId(), payment.completedAt())));
        settlements.findSettledBetween(payer, from, to).stream()
                .filter(settlement -> settlement.receivedAmount() > 0)
                .forEach(settlement -> expenses.add(new Expense(settlement.id(), settlement.requestId(), Kind.CASH,
                        settlement.receivedAmount(), settlement.fixerUserId(), settlement.settledAt())));
        return expenses;
    }
}
