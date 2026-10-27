package com.fixup.payments.api;

import java.time.Instant;
import java.util.UUID;

/** Immutable descriptive snapshot, not another payment or authorization grant. */
public record PaymentContext(UUID quotationId, UUID requestId, UUID propertyId, UUID ownerUserId,
        UUID fixerUserId, UUID managerUserId, String specialty, String concept,
        Instant capturedAt, String source) {
    public boolean complete() {
        return propertyId != null && specialty != null && !specialty.isBlank()
                && concept != null && !concept.isBlank();
    }
}
