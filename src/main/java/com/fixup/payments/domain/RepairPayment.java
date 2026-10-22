package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentConflictException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent payment state; individual simulator decisions are retained as immutable attempts. The
 * owner owes the payment; when the property is delegated, its real-estate manager may pay it too
 * (FR-UC-11), so the manager is kept with the payment.
 */
public record RepairPayment(UUID id, UUID quotationId, UUID requestId, UUID ownerUserId, UUID fixerUserId,
        long amount, RepairPaymentMethod paymentMethod, PaymentPurpose purpose, PaymentStatus status,
        Instant createdAt, Instant updatedAt, Instant completedAt, UUID managerUserId) {

    public RepairPayment(UUID id, UUID quotationId, UUID requestId, UUID ownerUserId, UUID fixerUserId,
            long amount, RepairPaymentMethod paymentMethod, PaymentPurpose purpose, PaymentStatus status,
            Instant createdAt, Instant updatedAt, Instant completedAt) {
        this(id, quotationId, requestId, ownerUserId, fixerUserId, amount, paymentMethod, purpose, status,
                createdAt, updatedAt, completedAt, null);
    }

    public RepairPayment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(quotationId, "quotationId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (amount <= 0) {
            throw new PaymentConflictException("INVALID_PAYMENT_AMOUNT", "Payment amount must be greater than zero");
        }
        if (status == PaymentStatus.APPROVED && completedAt == null) {
            throw new PaymentConflictException("PAYMENT_COMPLETION_REQUIRED", "An approved payment must be completed");
        }
        if (status != PaymentStatus.APPROVED && completedAt != null) {
            throw new PaymentConflictException("UNEXPECTED_PAYMENT_COMPLETION", "Only an approved payment is completed");
        }
    }

    public static RepairPayment pending(UUID id, UUID quotationId, UUID requestId, UUID ownerUserId,
            UUID fixerUserId, long amount, RepairPaymentMethod paymentMethod, PaymentPurpose purpose, Instant now) {
        return new RepairPayment(id, quotationId, requestId, ownerUserId, fixerUserId, amount, paymentMethod,
                purpose, PaymentStatus.PENDING, now, now, null);
    }

    /** The delegated manager of the property, who may pay on the owner's behalf. */
    public RepairPayment managedBy(UUID managerUserId) {
        return new RepairPayment(id, quotationId, requestId, ownerUserId, fixerUserId, amount, paymentMethod,
                purpose, status, createdAt, updatedAt, completedAt, managerUserId);
    }

    /** Only the owner, or the manager the payment was opened for, settles it. */
    public boolean isPayableBy(UUID userId) {
        return ownerUserId.equals(userId) || (managerUserId != null && managerUserId.equals(userId));
    }

    /** Repeating approval is deliberately a no-op so future HTTP retries cannot duplicate effects. */
    public RepairPayment approve(Instant now) {
        if (status == PaymentStatus.APPROVED) {
            return this;
        }
        return transition(PaymentStatus.APPROVED, now);
    }

    public RepairPayment decline(Instant now) {
        return transition(PaymentStatus.DECLINED, now);
    }

    public RepairPayment fail(Instant now) {
        return transition(PaymentStatus.ERROR, now);
    }

    public RepairPayment keepPending(Instant now) {
        return transition(PaymentStatus.PENDING, now);
    }

    private RepairPayment transition(PaymentStatus target, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status == PaymentStatus.APPROVED) {
            throw new PaymentConflictException("PAYMENT_ALREADY_APPROVED", "An approved payment cannot change status");
        }
        return new RepairPayment(id, quotationId, requestId, ownerUserId, fixerUserId, amount, paymentMethod,
                purpose, target, createdAt, now, target == PaymentStatus.APPROVED ? now : null, managerUserId);
    }
}
