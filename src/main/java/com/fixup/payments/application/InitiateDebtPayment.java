package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.DebtNotFoundException;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebts;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.RepairPayments;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitiateDebtPayment {
    private final OwnerDebts debts;
    private final RepairPayments payments;
    private final Clock clock;
    private final PaymentAuthorization authorization;

    InitiateDebtPayment(OwnerDebts debts, RepairPayments payments, Clock clock, PaymentAuthorization authorization) {
        this.debts = debts;
        this.payments = payments;
        this.clock = clock;
        this.authorization = authorization;
    }

    @Transactional
    public PaymentDetail execute(CurrentActor actor, UUID debtId) {
        var visible = debts.findById(debtId).orElseThrow(DebtNotFoundException::new);
        authorization.requirePayer(actor, visible.quotationId(), visible.ownerUserId());
        var debt = debts.findByIdForUpdate(debtId)
                .orElseThrow(DebtNotFoundException::new);
        if (debt.status() != DebtStatus.OUTSTANDING) {
            throw new PaymentConflictException("DEBT_ALREADY_PAID", "The debt is already paid");
        }
        var existing = payments.findByQuotationAndPurpose(debt.quotationId(),
                PaymentPurpose.OUTSTANDING_BALANCE);
        if (existing.isPresent()) {
            var payment = existing.orElseThrow();
            if (payment.amount() != debt.amount()
                    || !payment.requestId().equals(debt.requestId())
                    || !payment.ownerUserId().equals(debt.ownerUserId())
                    || !payment.fixerUserId().equals(debt.fixerUserId())
                    || payment.paymentMethod() != RepairPaymentMethod.TRANSFER) {
                throw new PaymentConflictException("PAYMENT_INITIATION_CONFLICT",
                        "The existing debt payment does not match this debt");
            }
            return PaymentDetail.from(payment);
        }
        var payment = RepairPayment.pending(UUID.randomUUID(), debt.quotationId(),
                debt.requestId(), debt.ownerUserId(), debt.fixerUserId(), debt.amount(),
                RepairPaymentMethod.TRANSFER, PaymentPurpose.OUTSTANDING_BALANCE, clock.instant());
        payments.create(payment);
        return PaymentDetail.from(payment);
    }
}
