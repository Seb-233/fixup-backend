package com.fixup.quotations.application;

import com.fixup.jobs.api.JobOpening;
import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.payments.api.PaymentApproved;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates approved transfer payments with Jobs without making Payments depend on Jobs. */
@Component
class OpenJobOnPaymentApproved {
    private final JobOpening jobs;

    OpenJobOnPaymentApproved(JobOpening jobs) {
        this.jobs = jobs;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PaymentApproved event) {
        jobs.open(new JobOpeningCommand(event.requestId(), event.quotationId(), event.fixerUserId(),
                event.ownerUserId(), event.approvedAt()));
    }
}
