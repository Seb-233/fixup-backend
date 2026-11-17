package com.fixup.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** INT-01: una apertura del checkout de Wompi para un pago; inmutable una vez creada. */
public record WompiCheckout(String reference, UUID paymentId, long amountInCents, UUID createdByUserId,
        Instant createdAt) {
    public WompiCheckout {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (amountInCents <= 0) {
            throw new IllegalArgumentException("amountInCents must be positive");
        }
    }
}
