package com.fixup.quotations.application;

import com.fixup.jobs.api.JobOpening;
import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.requests.api.PaymentMethod;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Routes accepted CASH quotations to Jobs within the acceptance transaction. */
@Component
class OpenJobOnAcceptedQuotation {
    private final JobOpening jobs;

    OpenJobOnAcceptedQuotation(JobOpening jobs) {
        this.jobs = jobs;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(QuotationAccepted event) {
        if (event.paymentMethod() != PaymentMethod.CASH) {
            return;
        }
        jobs.open(new JobOpeningCommand(event.requestId(), event.quotationId(), event.fixerUserId(),
                event.ownerUserId(), event.acceptedAt()));
    }
}
