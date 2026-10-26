package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.PaymentNotFoundException;
import com.fixup.payments.domain.RepairPayments;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetRepairPayment {
    private final RepairPayments payments;
    private final PaymentAuthorization authorization;
    private final com.fixup.payments.domain.SimulatedPaymentAttempts attempts;

    GetRepairPayment(RepairPayments payments, PaymentAuthorization authorization,
            com.fixup.payments.domain.SimulatedPaymentAttempts attempts) {
        this.payments = payments;
        this.authorization = authorization;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public PaymentDetail execute(CurrentActor actor, UUID paymentId) {
        var payment = payments.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
        authorization.requireReader(actor, payment);
        return PaymentDetail.from(payment, authorization.context(payment.quotationId()));
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentAttemptResult> history(CurrentActor actor, UUID paymentId) {
        execute(actor, paymentId);
        return attempts.findByPayment(paymentId).stream().map(PaymentAttemptResult::of).toList();
    }
}
