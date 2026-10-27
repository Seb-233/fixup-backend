package com.fixup.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit row; attempts are never updated or deleted by the application. */
public record SimulatedPaymentAttempt(UUID id, UUID paymentId, PaymentStatus requestedOutcome,
        PaymentStatus resultingStatus, UUID attemptedByUserId, Instant attemptedAt,
        UUID operationKey, String reference, Long amount, com.fixup.payments.api.PaymentMethod instrument,
        String scenario, String gatewayTransactionId, String failureReason) {
    /** Pre-enrichment rows have no invented gateway/operation metadata. */
    public SimulatedPaymentAttempt(UUID id, UUID paymentId, PaymentStatus requestedOutcome,
            PaymentStatus resultingStatus, UUID attemptedByUserId, Instant attemptedAt) {
        this(id, paymentId, requestedOutcome, resultingStatus, attemptedByUserId, attemptedAt,
                null, null, null, null, null, null, null);
    }
    public SimulatedPaymentAttempt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(requestedOutcome, "requestedOutcome must not be null");
        Objects.requireNonNull(resultingStatus, "resultingStatus must not be null");
        Objects.requireNonNull(attemptedByUserId, "attemptedByUserId must not be null");
        Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");
    }
}
