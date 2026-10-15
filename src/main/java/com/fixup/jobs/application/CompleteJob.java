package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.api.JobCompleted;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.Jobs;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The verified fixer closes the job. Synchronous listeners settle its payment and complete the
 * repair request in the same transaction.
 */
@Service
public class CompleteJob {
    private final Jobs jobs;
    private final FixerEligibility eligibility;
    private final ApplicationEventPublisher events;

    CompleteJob(Jobs jobs, FixerEligibility eligibility, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.eligibility = eligibility;
        this.events = events;
    }

    @Transactional
    public JobSummary execute(CurrentActor actor, UUID jobId, Long receivedAmount) {
        JobAccess.requireActiveFixer(actor);
        eligibility.requireVerified(actor);
        // Lock the row: two concurrent completions must not release the escrow twice.
        var job = jobs.findByIdForUpdate(jobId).orElseThrow(JobNotFoundException::new);
        job.requireExecutedBy(actor.internalUserId());
        if (job.paymentMethod().equals("CASH")) {
            if (receivedAmount == null || receivedAmount < 0 || receivedAmount > job.agreedAmount()) {
                throw new com.fixup.jobs.api.JobConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                        "Cash received must be between zero and the agreed amount");
            }
        } else if (receivedAmount != null) {
            throw new com.fixup.jobs.api.JobConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                    "Transfer jobs do not accept a cash received amount");
        }

        var completed = job.complete(Instant.now());
        jobs.update(completed);
        events.publishEvent(new JobCompleted(completed.id(), completed.quotationId(),
                completed.requestId(), completed.ownerUserId(), completed.fixerUserId(),
                completed.paymentMethod(), completed.agreedAmount(), receivedAmount,
                completed.completedAt()));
        return JobSummary.of(completed);
    }
}
