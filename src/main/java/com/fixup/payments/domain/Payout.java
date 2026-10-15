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
        Instant requestedAt, Instant transferredAt) {

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
                requestedAt, now);
    }
}
