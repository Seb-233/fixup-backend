package com.fixup.quotations.api;

import java.time.Instant;
import java.util.UUID;

public record QuotationSubmitted(
        UUID quotationId,
        UUID requestId,
        UUID fixerUserId,
        UUID ownerUserId,
        long amount,
        Instant at) {
}
