package com.fixup.quotations.application;

import com.fixup.requests.api.PaymentMethod;
import java.util.UUID;

/** Outcome of accepting a quotation, including the next financial step for the client. */
public record AcceptedQuotation(UUID quotationId, UUID requestId, PaymentMethod paymentMethod,
        UUID paymentId) {
}
