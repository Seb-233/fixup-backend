package com.fixup.payments.application;

import com.fixup.payments.api.PaymentContext;
import com.fixup.payments.api.PaymentContextRegistration;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.PaymentContexts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class CapturePaymentContext implements PaymentContextRegistration {
    private final PaymentContexts contexts;
    CapturePaymentContext(PaymentContexts contexts) { this.contexts = contexts; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(PaymentContext context) {
        if (!context.complete() || !"ACCEPTANCE".equals(context.source())) {
            throw new PaymentConflictException("PAYMENT_CONTEXT_REQUIRED", "Accepted repair context is required");
        }
        var previous = contexts.findByQuotation(context.quotationId());
        if (previous.isPresent()) {
            var c = previous.orElseThrow();
            if (!c.requestId().equals(context.requestId()) || !c.ownerUserId().equals(context.ownerUserId())
                    || !c.fixerUserId().equals(context.fixerUserId()) || !java.util.Objects.equals(c.propertyId(), context.propertyId())) {
                throw new PaymentConflictException("PAYMENT_CONTEXT_CONFLICT", "Accepted repair context cannot change");
            }
            return;
        }
        contexts.create(context);
    }
}
