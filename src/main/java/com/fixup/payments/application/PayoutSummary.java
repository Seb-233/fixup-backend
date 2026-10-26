package com.fixup.payments.application;

import com.fixup.payments.api.PayoutStatus;
import com.fixup.payments.domain.Payout;
import java.time.Instant;
import java.util.UUID;

/** Read model of a transfer request. */
public record PayoutSummary(UUID id, long amount, int earningCount, PayoutStatus status,
        Instant requestedAt, Instant transferredAt, UUID operationKey, String detailStatus,
        java.util.List<com.fixup.payments.domain.PayoutEntry> earnings) {

    static PayoutSummary of(Payout payout) {
        return of(payout, java.util.List.of());
    }
    static PayoutSummary of(Payout payout, java.util.List<com.fixup.payments.domain.PayoutEntry> entries) {
        return new PayoutSummary(payout.id(), payout.amount(), payout.earningCount(),
                payout.status(), payout.requestedAt(), payout.transferredAt(), payout.operationKey(),
                payout.operationKey() == null ? "LEGACY_UNAVAILABLE" : "RECORDED", entries);
    }
}
