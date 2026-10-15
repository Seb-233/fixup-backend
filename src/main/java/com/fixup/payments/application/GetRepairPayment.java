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

    GetRepairPayment(RepairPayments payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public PaymentDetail execute(CurrentActor actor, UUID paymentId) {
        var payment = payments.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
        PaymentAccess.requireCanRead(actor, payment);
        return PaymentDetail.from(payment);
    }
}
