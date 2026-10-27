package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
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
    private final PaymentAuthorization authorization;
    private final com.fixup.payments.domain.PaymentGateway gateway;
    private final org.springframework.core.env.Environment environment;

    SimulateRepairPayment(RepairPayments payments, SimulatedPaymentAttempts attempts,
            FixerEarnings earnings, OwnerDebts debts, ApplicationEventPublisher events, Clock clock,
            PaymentAuthorization authorization, com.fixup.payments.domain.PaymentGateway gateway,
            org.springframework.core.env.Environment environment) {
        this.payments = payments;
        this.attempts = attempts;
        this.earnings = earnings;
        this.debts = debts;
        this.events = events;
        this.clock = clock;
        this.authorization = authorization;
        this.gateway = gateway;
        this.environment = environment;
    }

    @Transactional
    public PaymentDetail execute(CurrentActor actor, UUID paymentId, PaymentSimulationOutcome outcome) {
        if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("test"))) {
            throw new com.fixup.payments.api.PaymentAccessDeniedException();
        }
        var current = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentNotFoundException::new);
        authorization.requirePayer(actor, current);
        requireSupported(current);

        if (current.status() == PaymentStatus.APPROVED) {
            if (outcome == PaymentSimulationOutcome.APPROVED) {
                return PaymentDetail.from(current);
            }
            throw new PaymentConflictException("PAYMENT_ALREADY_APPROVED",
                    "An approved payment cannot change status");
        }

        String scenario = switch (outcome) {
            case APPROVED -> "APPROVE";
            case DECLINED -> "DECLINE_BANK";
            case PENDING -> "PENDING";
            case ERROR -> "ERROR";
        };
        return process(actor, current, new PaymentAttemptCommand(current.reference(), current.amount(),
                com.fixup.payments.api.PaymentMethod.SANDBOX_CARD, UUID.randomUUID()), scenario).detail();
    }

    @Transactional
    public PaymentAttemptResult attempt(CurrentActor actor, UUID paymentId, PaymentAttemptCommand command) {
        var current = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentNotFoundException::new);
        authorization.requirePayer(actor, current);
        requireSupported(current);
        var previous = attempts.findByOperation(paymentId, command.operationKey());
        if (previous.isPresent()) {
            var a = previous.orElseThrow();
            if (!java.util.Objects.equals(a.reference(), command.reference())
                    || !java.util.Objects.equals(a.amount(), command.amount())
                    || a.instrument() != command.instrument()
                    || !a.attemptedByUserId().equals(actor.internalUserId())) {
                throw new PaymentConflictException("IDEMPOTENCY_CONFLICT", "Operation key already used with different content or actor");
            }
            return PaymentAttemptResult.of(a);
        }
        if (!current.reference().equals(command.reference())) {
            throw new PaymentConflictException("PAYMENT_REFERENCE_MISMATCH", "The reference does not match this payment");
        }
        if (current.amount() != command.amount()) {
            throw new PaymentConflictException("PAYMENT_AMOUNT_MISMATCH", "The full exact payment amount is required");
        }
        if (current.status() == PaymentStatus.APPROVED) {
            throw new PaymentConflictException("PAYMENT_ALREADY_APPROVED", "An approved payment cannot be paid again");
        }
        return process(actor, current, command, environment.getProperty("fixup.payments.sandbox.scenario", "APPROVE")).result();
    }

    private Processed process(CurrentActor actor, RepairPayment current, PaymentAttemptCommand command,
            String scenario) {
        var result = gateway.charge(new com.fixup.payments.domain.PaymentGateway.Attempt(current.id(),
                command.reference(), command.amount(), command.instrument(), scenario));
        // PostgreSQL stores microseconds: initial responses must equal subsequent persisted replays.
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        RepairPayment updated = switch (result.status()) {
            case APPROVED -> current.approve(now);
            case DECLINED -> current.decline(now);
            case PENDING -> current.keepPending(now);
            case ERROR -> current.fail(now);
        };

        var audit = new SimulatedPaymentAttempt(UUID.randomUUID(), current.id(),
                result.status(), updated.status(), actor.internalUserId(), now, command.operationKey(),
                command.reference(), command.amount(), command.instrument(), scenario, result.transactionId(), result.failureReason());
        attempts.create(audit);
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
        }
        notifyParticipants(actor, updated, result.failureReason());
        return new Processed(PaymentDetail.from(updated, authorization.context(updated.quotationId())), PaymentAttemptResult.of(audit));
    }

    private record Processed(PaymentDetail detail, PaymentAttemptResult result) { }

    private void notifyParticipants(CurrentActor actor, RepairPayment payment, String failureReason) {
        boolean approved = payment.status() == PaymentStatus.APPROVED;
        if (!approved && payment.status() != PaymentStatus.DECLINED && payment.status() != PaymentStatus.ERROR) return;
        var type = approved ? com.fixup.notifications.api.NotificationType.PAYMENT_APPROVED
                : com.fixup.notifications.api.NotificationType.PAYMENT_DECLINED;
        var recipients = new java.util.LinkedHashSet<UUID>();
        recipients.add(actor.internalUserId());
        recipients.add(payment.ownerUserId());
        if (approved) recipients.add(payment.fixerUserId());
        for (var recipient : recipients) {
            boolean beneficiary = recipient.equals(payment.fixerUserId());
            boolean service = payment.purpose() == PaymentPurpose.SERVICE;
            String body;
            if (!approved) {
                body = (failureReason == null ? "El intento no fue aprobado" : failureReason) + ". Puedes volver a intentarlo.";
            } else if (beneficiary) {
                body = service ? "El servicio quedó pagado. El trabajo está habilitado y el ingreso retenido hasta el cierre."
                        : "Se liquidó el saldo pendiente. El ingreso está disponible.";
            } else if (recipient.equals(payment.ownerUserId()) && !recipient.equals(actor.internalUserId())) {
                body = "Tu administrador registró el pago simulado completo de la reparación: " + payment.reference();
            } else {
                body = "Pago simulado completo registrado: " + payment.reference();
            }
            events.publishEvent(new com.fixup.notifications.api.NotificationRequested(recipient, type,
                    approved ? "Pago aprobado" : "Pago no aprobado",
                    body, beneficiary ? (service ? "/jobs/me" : "/payments/earnings") : "/requests/" + payment.requestId(), payment.id(), "PAYMENT",
                    java.util.Map.of("attemptedByUserId", actor.internalUserId().toString(),
                            "ownerUserId", payment.ownerUserId().toString(), "status", payment.status().name())));
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
