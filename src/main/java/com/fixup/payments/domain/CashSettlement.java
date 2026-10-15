package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentConflictException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable record of cash handed directly to the fixer. */
public record CashSettlement(UUID id, UUID jobId, UUID quotationId, UUID requestId,
        UUID ownerUserId, UUID fixerUserId, long agreedAmount, long receivedAmount,
        long outstandingAmount, Instant settledAt) {

    public CashSettlement {
        Objects.requireNonNull(id);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(quotationId);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(ownerUserId);
        Objects.requireNonNull(fixerUserId);
        Objects.requireNonNull(settledAt);
        if (agreedAmount <= 0 || receivedAmount < 0 || receivedAmount > agreedAmount
                || outstandingAmount != agreedAmount - receivedAmount) {
            throw new PaymentConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                    "Cash settlement amounts are inconsistent");
        }
    }

    public static CashSettlement create(UUID id, UUID jobId, UUID quotationId, UUID requestId,
            UUID ownerUserId, UUID fixerUserId, long agreedAmount, long receivedAmount,
            Instant settledAt) {
        if (agreedAmount <= 0 || receivedAmount < 0 || receivedAmount > agreedAmount) {
            throw new PaymentConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                    "Cash received must be between zero and the agreed amount");
        }
        return new CashSettlement(id, jobId, quotationId, requestId, ownerUserId, fixerUserId,
                agreedAmount, receivedAmount, agreedAmount - receivedAmount, settledAt);
    }
}
