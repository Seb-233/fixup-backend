package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.api.JobCompleted;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.Jobs;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-20: el técnico cierra el trabajo y con eso libera el dinero retenido. Solo puede hacerlo
 * un Fixer verificado, que es la regla que module-rules exige invocar antes de dejarlo trabajar.
 */
@Service
public class CompleteJob {
    private final Jobs jobs;
    private final FixerEligibility eligibility;
    private final RepairRequestDirectory requests;
    private final ApplicationEventPublisher events;

    CompleteJob(Jobs jobs, FixerEligibility eligibility, RepairRequestDirectory requests,
            ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.eligibility = eligibility;
        this.requests = requests;
        this.events = events;
    }

    @Transactional
    public JobSummary execute(CurrentActor actor, UUID jobId) {
        JobAccess.requireActiveFixer(actor);
        eligibility.requireVerified(actor);
        // Lock the row: two concurrent completions must not release the escrow twice.
        var job = jobs.findByIdForUpdate(jobId).orElseThrow(JobNotFoundException::new);
        job.requireExecutedBy(actor.internalUserId());

        var completed = job.complete(Instant.now());
        jobs.update(completed);
        // FR-UC-08: este es el único cierre del sistema. La solicitud pasa a COMPLETED en la misma
        // transacción que libera el escrow, igual que la asignación viaja con la aceptación de la
        // cotización. Así nunca queda una solicitud asignada para siempre con el dinero retenido.
        requests.complete(completed.requestId());
        events.publishEvent(new JobCompleted(completed.id(), completed.quotationId(),
                completed.fixerUserId(), completed.completedAt()));
        return JobSummary.of(completed);
    }
}
