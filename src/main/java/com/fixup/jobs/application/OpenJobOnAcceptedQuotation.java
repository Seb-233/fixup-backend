package com.fixup.jobs.application;

import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.Jobs;
import com.fixup.quotations.api.QuotationAccepted;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-20: aceptar una cotización abre el trabajo. El listener participa en la transacción de
 * la aceptación, de modo que nunca existe una cotización aceptada sin su trabajo.
 */
@Component
class OpenJobOnAcceptedQuotation {
    private final Jobs jobs;

    OpenJobOnAcceptedQuotation(Jobs jobs) {
        this.jobs = jobs;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(QuotationAccepted event) {
        // Una cotización solo se acepta una vez, pero el guardado es idempotente por si el
        // evento llegara repetido.
        if (jobs.existsByQuotation(event.quotationId())) {
            return;
        }
        jobs.create(Job.assigned(UUID.randomUUID(), event.requestId(), event.quotationId(),
                event.fixerUserId(), event.ownerUserId(), Instant.now()));
    }
}
