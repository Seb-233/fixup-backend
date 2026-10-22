package com.fixup.payments.application;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentInitiation;
import com.fixup.payments.api.PaymentInitiationCommand;
import com.fixup.payments.api.PaymentInitiationResult;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.RepairPayments;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates the pending service payment that gates a TRANSFER quotation acceptance. */
@Service
class InitiateRepairPayment implements PaymentInitiation {
    private final RepairPayments payments;

    InitiateRepairPayment(RepairPayments payments) {
        this.payments = payments;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentInitiationResult initiate(PaymentInitiationCommand command) {
        var method = RepairPaymentMethod.fromCanonical(command.paymentMethod());
        if (method != RepairPaymentMethod.TRANSFER) {
            throw new PaymentConflictException("CASH_PAYMENT_NOT_INITIATED",
                    "Cash payments are settled after the job is completed");
        }

        var existing = payments.findByQuotationAndPurpose(command.quotationId(), PaymentPurpose.SERVICE);
        if (existing.isPresent()) {
            var payment = existing.orElseThrow();
            if (!matches(payment, command, method)) {
                throw new PaymentConflictException("PAYMENT_INITIATION_CONFLICT",
                        "The service payment already exists with different immutable data");
            }
            return result(payment);
        }

        var payment = RepairPayment.pending(UUID.randomUUID(), command.quotationId(), command.requestId(),
                command.ownerUserId(), command.fixerUserId(), command.amount(), method,
                PaymentPurpose.SERVICE, command.occurredAt()).managedBy(command.managerUserId());
        payments.create(payment);
        return result(payment);
    }

    private boolean matches(RepairPayment payment, PaymentInitiationCommand command,
            RepairPaymentMethod method) {
        return payment.requestId().equals(command.requestId())
                && payment.ownerUserId().equals(command.ownerUserId())
                && payment.fixerUserId().equals(command.fixerUserId())
                && payment.amount() == command.amount()
                && payment.paymentMethod() == method
                && payment.purpose() == PaymentPurpose.SERVICE;
    }

    private PaymentInitiationResult result(RepairPayment payment) {
        return new PaymentInitiationResult(payment.id(),
                PaymentInitiationResult.Status.valueOf(payment.status().name()));
    }
}
