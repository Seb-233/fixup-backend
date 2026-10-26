package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.api.JobCompleted;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import com.fixup.notifications.api.NotificationType;
import com.fixup.payments.api.PaymentSettlement;
import com.fixup.payments.api.PaymentSettlementCommand;
import com.fixup.properties.api.PropertyWorkHistory;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestCompletionCommand;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The verified fixer closes the job. Public synchronous contracts settle its payment and complete
 * the repair request in the same transaction, together with the log and property work history.
 */
@Service
public class CompleteJob {
    private final Jobs jobs;
    private final JobRecords records;
    private final FixerEligibility eligibility;
    private final RepairRequestDirectory requests;
    private final PaymentSettlement settlement;
    private final PropertyWorkHistory workHistory;
    private final JobNotices notices;
    private final ApplicationEventPublisher events;

    CompleteJob(Jobs jobs, JobRecords records, FixerEligibility eligibility, RepairRequestDirectory requests,
            PaymentSettlement settlement, PropertyWorkHistory workHistory, JobNotices notices,
            ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.records = records;
        this.eligibility = eligibility;
        this.requests = requests;
        this.settlement = settlement;
        this.workHistory = workHistory;
        this.notices = notices;
        this.events = events;
    }

    @Transactional
    public JobSummary execute(CurrentActor actor, UUID jobId) {
        return execute(actor, jobId, null, null);
    }

    @Transactional
    public JobSummary execute(CurrentActor actor, UUID jobId, Long receivedAmount) {
        return execute(actor, jobId, receivedAmount, null);
    }

    @Transactional
    public JobSummary execute(CurrentActor actor, UUID jobId, Long receivedAmount, String note) {
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

        var completed = job.complete(note, Instant.now());
        jobs.update(completed);
        records.log(new JobLogEntry(UUID.randomUUID(), jobId, completed.status(), completed.progressNote(),
                actor.internalUserId(), completed.completedAt()));

        // Preserve lock/write order: job, financial settlement, then repair request.
        settlement.settle(new PaymentSettlementCommand(completed.id(), completed.quotationId(),
                completed.requestId(), completed.ownerUserId(), completed.fixerUserId(),
                completed.paymentMethod(), completed.agreedAmount(), receivedAmount,
                completed.completedAt()));
        requests.complete(new RepairRequestCompletionCommand(completed.requestId(), completed.ownerUserId(),
                completed.fixerUserId(), completed.completedAt()));

        var request = requests.require(completed.requestId());
        if (request.propertyId() != null) {
            workHistory.recordCompletedWork(new PropertyWorkHistory.CompletedWork(request.propertyId(), completed.id(),
                    completed.requestId(), request.specialty().name(),
                    request.title() == null ? "Trabajo de mantenimiento" : request.title(),
                    completed.fixerUserId(), completed.completedAt()));
        }
        // Visits still react synchronously. Any failure rolls back all mandatory SQL above.
        events.publishEvent(new JobCompleted(completed.id(), completed.quotationId(),
                completed.requestId(), completed.ownerUserId(), completed.fixerUserId(),
                completed.paymentMethod(), completed.agreedAmount(), receivedAmount, completed.completedAt()));
        notices.progressed(completed, request, NotificationType.JOB_COMPLETED, "Trabajo terminado",
                "El técnico cerró \"" + request.title() + "\". Revisa las evidencias y califica el servicio.");
        return JobSummary.of(completed);
    }
}
