package com.fixup.payments.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Published by payments when a simulated transfer is approved and a job may be opened. */
public record PaymentApproved(UUID paymentId, UUID quotationId, UUID requestId, UUID ownerUserId,
        UUID fixerUserId, long amount, ApprovedPaymentPurpose purpose, Instant approvedAt) {

    public PaymentApproved(UUID paymentId, UUID quotationId, UUID requestId, UUID ownerUserId,
            UUID fixerUserId, long amount, Instant approvedAt) {
        this(paymentId, quotationId, requestId, ownerUserId, fixerUserId, amount,
                ApprovedPaymentPurpose.SERVICE, approvedAt);
    }

    public PaymentApproved {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(quotationId, "quotationId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
