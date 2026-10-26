package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PayoutStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: la solicitud de transferencia del técnico a su cuenta bancaria.
 *
 * New payouts pass from REQUESTED to TRANSFERRED within one simulated operation. Historical
 * REQUESTED rows remain readable. No banking provider is called.
 */
public record Payout(UUID id, UUID fixerUserId, long amount, int earningCount, PayoutStatus status,
        Instant requestedAt, Instant transferredAt, UUID operationKey) {

    public Payout(UUID id, UUID fixerUserId, long amount, int earningCount, PayoutStatus status, Instant requestedAt, Instant transferredAt) {
        this(id, fixerUserId, amount, earningCount, status, requestedAt, transferredAt, null);
    }

    public static Payout requested(UUID id, UUID fixerUserId, long amount, int earningCount, Instant now, UUID key) {
        java.util.Objects.requireNonNull(key, "operationKey");
        var draft = requested(id, fixerUserId, amount, earningCount, now);
        return new Payout(draft.id(), draft.fixerUserId(), draft.amount(), draft.earningCount(), draft.status(), now, null, key);
    }

    public static Payout requested(UUID id, UUID fixerUserId, long amount, int earningCount,
            Instant now) {
        if (amount <= 0) {
            throw new PaymentConflictException("NO_AVAILABLE_BALANCE",
                    "There is no available balance to transfer");
        }
        return new Payout(id, fixerUserId, amount, earningCount, PayoutStatus.REQUESTED, now, null);
    }

    public Payout transferred(Instant now) {
        if (status != PayoutStatus.REQUESTED) {
            throw new PaymentConflictException("PAYOUT_ALREADY_TRANSFERRED",
                    "The simulated payout has already been transferred");
        }
        return new Payout(id, fixerUserId, amount, earningCount, PayoutStatus.TRANSFERRED,
                requestedAt, now, operationKey);
    }
}
