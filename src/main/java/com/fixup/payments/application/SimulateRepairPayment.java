package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.api.PaymentApproved;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentNotFoundException;
import com.fixup.payments.api.PaymentSimulationOutcome;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.RepairPayments;
import com.fixup.payments.domain.SimulatedPaymentAttempt;
import com.fixup.payments.domain.SimulatedPaymentAttempts;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies one simulated provider outcome while holding the payment row lock. */
@Service
public class SimulateRepairPayment {
    private final RepairPayments payments;
    private final SimulatedPaymentAttempts attempts;
    private final FixerEarnings earnings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SimulateRepairPayment(RepairPayments payments, SimulatedPaymentAttempts attempts,
            FixerEarnings earnings, ApplicationEventPublisher events, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.earnings = earnings;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public PaymentDetail execute(CurrentActor actor, UUID paymentId, PaymentSimulationOutcome outcome) {
        var current = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentNotFoundException::new);
        PaymentAccess.requireCanSimulate(actor, current);
        requireSupported(current);

        if (current.status() == PaymentStatus.APPROVED) {
            if (outcome == PaymentSimulationOutcome.APPROVED) {
                return PaymentDetail.from(current);
            }
            throw new PaymentConflictException("PAYMENT_ALREADY_APPROVED",
                    "An approved payment cannot change status");
        }

        Instant now = clock.instant();
        RepairPayment updated = switch (outcome) {
            case APPROVED -> current.approve(now);
            case DECLINED -> current.decline(now);
            case PENDING -> current.keepPending(now);
            case ERROR -> current.fail(now);
        };

        attempts.create(new SimulatedPaymentAttempt(UUID.randomUUID(), current.id(),
                PaymentStatus.valueOf(outcome.name()), updated.status(), actor.internalUserId(), now));
        payments.update(updated);

        if (updated.status() == PaymentStatus.APPROVED) {
            createHeldEarning(updated, now);
            events.publishEvent(new PaymentApproved(updated.id(), updated.quotationId(),
                    updated.requestId(), updated.ownerUserId(), updated.fixerUserId(), updated.amount(), now));
        }
        return PaymentDetail.from(updated);
    }

    private void requireSupported(RepairPayment payment) {
        if (payment.purpose() != PaymentPurpose.SERVICE) {
            throw new PaymentConflictException("PAYMENT_PURPOSE_NOT_SUPPORTED",
                    "Only service payments can be simulated in this operation");
        }
        if (payment.paymentMethod() != RepairPaymentMethod.TRANSFER) {
            throw new PaymentConflictException("PAYMENT_METHOD_NOT_SUPPORTED",
                    "Only transfer payments can be simulated in this operation");
        }
    }

    private void createHeldEarning(RepairPayment payment, Instant now) {
        if (earnings.existsByQuotation(payment.quotationId())) {
            throw new PaymentConflictException("EARNING_ALREADY_EXISTS",
                    "The approved payment already has an earning");
        }
        earnings.create(FixerEarning.held(UUID.randomUUID(), payment.quotationId(),
                payment.fixerUserId(), payment.amount(), now));
    }
}
