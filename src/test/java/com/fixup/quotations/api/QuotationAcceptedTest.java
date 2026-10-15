package com.fixup.quotations.api;

import com.fixup.requests.api.PaymentMethod;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationAcceptedTest {

    @Test
    void eventRetainsTheOriginalDataAndCarriesThePaymentMethod() {
        UUID quotationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID fixerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant acceptedAt = Instant.parse("2026-10-14T12:00:00Z");

        var event = new QuotationAccepted(quotationId, requestId, fixerId, ownerId,
                450_000L, acceptedAt, PaymentMethod.TRANSFER);

        assertThat(event.quotationId()).isEqualTo(quotationId);
        assertThat(event.requestId()).isEqualTo(requestId);
        assertThat(event.fixerUserId()).isEqualTo(fixerId);
        assertThat(event.ownerUserId()).isEqualTo(ownerId);
        assertThat(event.amount()).isEqualTo(450_000L);
        assertThat(event.acceptedAt()).isEqualTo(acceptedAt);
        assertThat(event.paymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
    }
}
