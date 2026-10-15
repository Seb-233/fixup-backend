package com.fixup.payments.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Complete, immutable job terms required for financial settlement. */
public record PaymentSettlementCommand(UUID jobId, UUID quotationId, UUID requestId,
        UUID ownerUserId, UUID fixerUserId, String paymentMethod, long agreedAmount,
        Long cashReceivedAmount, Instant completedAt) {

    public PaymentSettlementCommand {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(quotationId, "quotationId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        if (!paymentMethod.equals("TRANSFER") && !paymentMethod.equals("CASH")) {
            throw new IllegalArgumentException("Unsupported payment method");
        }
        if (agreedAmount <= 0) {
            throw new IllegalArgumentException("agreedAmount must be positive");
        }
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (cashReceivedAmount != null && cashReceivedAmount < 0) {
            throw new IllegalArgumentException("cashReceivedAmount must not be negative");
        }
    }
}
