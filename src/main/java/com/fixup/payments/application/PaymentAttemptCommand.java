package com.fixup.payments.application;

import com.fixup.payments.api.PaymentMethod;
import com.fixup.payments.api.PaymentConflictException;
import java.util.UUID;

/** Exact client intent; the provider outcome is deliberately absent. */
public record PaymentAttemptCommand(String reference, long amount, PaymentMethod instrument, UUID operationKey) {
    public PaymentAttemptCommand {
        if (reference == null || reference.isBlank() || amount <= 0 || instrument == null || operationKey == null) {
            throw new PaymentConflictException("INVALID_PAYMENT_ATTEMPT", "Reference, exact amount, instrument and operation key are required");
        }
    }
}
