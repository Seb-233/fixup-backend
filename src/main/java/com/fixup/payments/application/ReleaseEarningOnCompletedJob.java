package com.fixup.payments.application;

import com.fixup.jobs.api.JobCompleted;
import com.fixup.payments.api.PaymentSettlement;
import com.fixup.payments.api.PaymentSettlementCommand;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Routes a completed job to its financial settlement before the request is marked completed. */
@Component
class ReleaseEarningOnCompletedJob {
    private final PaymentSettlement settlement;

    ReleaseEarningOnCompletedJob(PaymentSettlement settlement) {
        this.settlement = settlement;
    }

    @EventListener
    @Order(1)
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(JobCompleted event) {
        settlement.settle(new PaymentSettlementCommand(event.jobId(), event.quotationId(),
                event.requestId(), event.ownerUserId(), event.fixerUserId(),
                event.paymentMethod(), event.agreedAmount(), event.receivedAmount(),
                event.completedAt()));
    }
}
