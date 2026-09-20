package com.fixup.quotations.application;

import com.fixup.quotations.api.QuotationStatus;
import com.fixup.quotations.domain.Quotation;
import java.time.Instant;
import java.util.UUID;

/** Read model of a quotation, as both the fixer who sent it and the owner who compares it see it. */
public record QuotationSummary(UUID id, UUID requestId, UUID fixerUserId, long amount, int estimatedDays,
        String message, QuotationStatus status, Instant createdAt) {

    static QuotationSummary of(Quotation quotation) {
        return new QuotationSummary(quotation.id(), quotation.requestId(), quotation.fixerUserId(),
                quotation.amount(), quotation.estimatedDays(), quotation.message(), quotation.status(),
                quotation.createdAt());
    }
}
