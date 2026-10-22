package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.payments.api.PaymentApproved;
import com.fixup.payments.api.ApprovedPaymentPurpose;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentNotFoundException;
import com.fixup.payments.api.PaymentSimulationOutcome;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebts;
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
    private final OwnerDebts debts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SimulateRepairPayment(RepairPayments payments, SimulatedPaymentAttempts attempts,
            FixerEarnings earnings, OwnerDebts debts, ApplicationEventPublisher events, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.earnings = earnings;
        this.debts = debts;
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
            if (updated.purpose() == PaymentPurpose.SERVICE) {
                createHeldEarning(updated, now);
            } else {
                settleDebt(updated, now);
            }
            events.publishEvent(new PaymentApproved(updated.id(), updated.quotationId(),
                    updated.requestId(), updated.ownerUserId(), updated.fixerUserId(), updated.amount(),
                    ApprovedPaymentPurpose.valueOf(updated.purpose().name()), now));
            notifyApproval(actor, updated);
        } else if (updated.status() == PaymentStatus.DECLINED || updated.status() == PaymentStatus.ERROR) {
            events.publishEvent(NotificationRequested.of(actor.internalUserId(), NotificationType.PAYMENT_DECLINED,
                    "Pago rechazado", "La transferencia simulada no se aprobó. Puedes intentarlo de nuevo.",
                    "/payments/" + updated.id(), updated.id(), "PAYMENT"));
        }
        return PaymentDetail.from(updated);
    }

    private void notifyApproval(CurrentActor actor, RepairPayment payment) {
        if (payment.purpose() == PaymentPurpose.SERVICE) {
            events.publishEvent(NotificationRequested.of(payment.fixerUserId(), NotificationType.PAYMENT_APPROVED,
                    "El cliente pagó el trabajo",
                    "Recibimos la transferencia. El trabajo ya aparece en Mis trabajos y el dinero queda "
                            + "retenido hasta que lo cierres.",
                    "/jobs/me", payment.id(), "PAYMENT"));
        } else {
            events.publishEvent(NotificationRequested.of(payment.fixerUserId(), NotificationType.PAYMENT_APPROVED,
                    "Te pagaron un saldo pendiente",
                    "El propietario pagó lo que faltaba de un trabajo en efectivo. Ya está disponible en tus ingresos.",
                    "/payments/earnings", payment.id(), "PAYMENT"));
        }
        if (!payment.ownerUserId().equals(actor.internalUserId())) {
            events.publishEvent(NotificationRequested.of(payment.ownerUserId(), NotificationType.PAYMENT_APPROVED,
                    "Se pagó una reparación de tu inmueble",
                    "Tu administrador inmobiliario pagó la reparación por transferencia.",
                    "/requests/" + payment.requestId(), payment.id(), "PAYMENT"));
        }
    }

    private void requireSupported(RepairPayment payment) {
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

    private void settleDebt(RepairPayment payment, Instant now) {
        var debt = debts.findByQuotationForUpdate(payment.quotationId())
                .orElseThrow(() -> new PaymentConflictException("DEBT_NOT_FOUND",
                        "The debt for this payment does not exist"));
        if (debt.status() != DebtStatus.OUTSTANDING || debt.amount() != payment.amount()
                || !debt.ownerUserId().equals(payment.ownerUserId())
                || !debt.fixerUserId().equals(payment.fixerUserId())
                || !debt.requestId().equals(payment.requestId())) {
            throw new PaymentConflictException("DEBT_PAYMENT_CONFLICT",
                    "The payment no longer matches an outstanding debt");
        }
        if (earnings.existsByQuotation(payment.quotationId())) {
            throw new PaymentConflictException("EARNING_ALREADY_EXISTS",
                    "The debt already has an earning");
        }
        debts.update(debt.settle(now));
        earnings.create(FixerEarning.held(UUID.randomUUID(), payment.quotationId(),
                payment.fixerUserId(), payment.amount(), now).release(now));
    }
}
