package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentContext;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PaymentContexts {
    Optional<PaymentContext> findByQuotation(UUID quotationId);
    Map<UUID, PaymentContext> findByQuotations(Collection<UUID> quotationIds);
    void create(PaymentContext context);
}
