package com.fixup.payments.application;

import com.fixup.payments.api.PayoutStatus;
import com.fixup.payments.domain.Payout;
import java.time.Instant;
import java.util.UUID;

/** Read model of a transfer request. */
public record PayoutSummary(UUID id, long amount, int earningCount, PayoutStatus status,
        Instant requestedAt) {

    static PayoutSummary of(Payout payout) {
        return new PayoutSummary(payout.id(), payout.amount(), payout.earningCount(),
                payout.status(), payout.requestedAt());
    }
}
