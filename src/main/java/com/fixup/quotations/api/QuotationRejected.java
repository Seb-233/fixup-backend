package com.fixup.quotations.api;

import java.time.Instant;
import java.util.UUID;

public record QuotationRejected(
        UUID quotationId,
        UUID requestId,
        UUID fixerUserId,
        UUID ownerUserId,
        Instant at) {
}
