package com.fixup.payments.api;

import java.util.Objects;
import java.util.UUID;

/** Minimal public result returned when a service payment lifecycle is initiated. */
public record PaymentInitiationResult(UUID paymentId, Status status) {

    public PaymentInitiationResult {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public enum Status {
        PENDING,
        APPROVED,
        DECLINED,
        ERROR
    }
}
