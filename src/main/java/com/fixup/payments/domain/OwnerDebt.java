package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentConflictException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OwnerDebt(UUID id, UUID ownerUserId, UUID fixerUserId, UUID requestId, UUID quotationId,
        long amount, DebtStatus status, Instant createdAt, Instant settledAt) {
    public OwnerDebt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(quotationId, "quotationId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (amount <= 0) {
            throw new PaymentConflictException("INVALID_DEBT_AMOUNT", "Debt amount must be greater than zero");
        }
        if (status == DebtStatus.PAID && settledAt == null) {
            throw new PaymentConflictException("DEBT_SETTLEMENT_REQUIRED", "A paid debt must be settled");
        }
        if (status == DebtStatus.OUTSTANDING && settledAt != null) {
            throw new PaymentConflictException("UNEXPECTED_DEBT_SETTLEMENT", "An outstanding debt cannot be settled");
        }
    }

    public static OwnerDebt outstanding(UUID id, UUID ownerUserId, UUID fixerUserId, UUID requestId,
            UUID quotationId, long amount, Instant now) {
        return new OwnerDebt(id, ownerUserId, fixerUserId, requestId, quotationId, amount,
                DebtStatus.OUTSTANDING, now, null);
    }

    public OwnerDebt settle(Instant now) {
        if (status == DebtStatus.PAID) {
            return this;
        }
        return new OwnerDebt(id, ownerUserId, fixerUserId, requestId, quotationId, amount,
                DebtStatus.PAID, createdAt, Objects.requireNonNull(now, "now must not be null"));
    }
}
