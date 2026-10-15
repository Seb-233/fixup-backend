package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Stub: replace with import from quotations module when ready. */
public record QuotationAcceptedEvent(
        UUID quotationId,
        UUID requestId,
        UUID fixerUserId,
        UUID ownerUserId,
        long amount,
        Instant acceptedAt) {
}
