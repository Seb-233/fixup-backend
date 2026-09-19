package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PayoutStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: la solicitud de transferencia del técnico a su cuenta bancaria. En el alcance
 * académico se registra el movimiento; no se integra una API bancaria real.
 */
public record Payout(UUID id, UUID fixerUserId, long amount, int earningCount, PayoutStatus status,
        Instant requestedAt) {

    public static Payout requested(UUID id, UUID fixerUserId, long amount, int earningCount,
            Instant now) {
        if (amount <= 0) {
            throw new PaymentConflictException("NO_AVAILABLE_BALANCE",
                    "There is no available balance to transfer");
        }
        return new Payout(id, fixerUserId, amount, earningCount, PayoutStatus.REQUESTED, now);
    }
}
