package com.fixup.jobs.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record JobOpeningCommand(UUID requestId, UUID quotationId, UUID fixerUserId,
        UUID ownerUserId, String paymentMethod, long agreedAmount, Instant occurredAt) {
    public JobOpeningCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(quotationId, "quotationId must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        if (!paymentMethod.equals("CASH") && !paymentMethod.equals("TRANSFER")) {
            throw new IllegalArgumentException("Unsupported payment method");
        }
        if (agreedAmount <= 0) {
            throw new IllegalArgumentException("agreedAmount must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
