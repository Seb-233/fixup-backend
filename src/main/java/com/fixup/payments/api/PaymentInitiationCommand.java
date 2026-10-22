package com.fixup.payments.api;

import com.fixup.payments.domain.RepairPaymentMethod;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Complete, immutable input for starting a repair-payment lifecycle. {@code managerUserId} is the
 * real-estate manager of a delegated property, or null.
 */
public record PaymentInitiationCommand(UUID quotationId, UUID requestId, UUID ownerUserId,
        UUID fixerUserId, long amount, String paymentMethod, Instant occurredAt, UUID managerUserId) {

    public PaymentInitiationCommand(UUID quotationId, UUID requestId, UUID ownerUserId,
            UUID fixerUserId, long amount, String paymentMethod, Instant occurredAt) {
        this(quotationId, requestId, ownerUserId, fixerUserId, amount, paymentMethod, occurredAt, null);
    }

    public PaymentInitiationCommand {
        Objects.requireNonNull(quotationId, "quotationId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        RepairPaymentMethod.fromCanonical(paymentMethod);
    }
}
