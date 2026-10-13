package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.api.JobCompleted;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import com.fixup.notifications.api.NotificationType;
import com.fixup.properties.api.PropertyWorkHistory;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-19 y FR-UC-20: el técnico cierra el trabajo. En la misma transacción se cierra la solicitud,
 * el trabajo entra al historial del inmueble (la evidencia del sello, FR-UC-07) y se libera el
 * dinero retenido si el propietario ya pagó. Solo puede hacerlo un Fixer verificado, que es la regla
 * que module-rules exige invocar antes de dejarlo trabajar.
 */
@Service
public class CompleteJob {
    private final Jobs jobs;
    private final JobRecords records;
    private final FixerEligibility eligibility;
    private final RepairRequestDirectory requests;
    private final PropertyWorkHistory workHistory;
    private final JobNotices notices;
    private final ApplicationEventPublisher events;

    CompleteJob(Jobs jobs, JobRecords records, FixerEligibility eligibility, RepairRequestDirectory requests,
            PropertyWorkHistory workHistory, JobNotices notices, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.records = records;
        this.eligibility = eligibility;
        this.requests = requests;
        this.workHistory = workHistory;
        this.notices = notices;
        this.events = events;
    }

    @Transactional
    public JobSummary execute(CurrentActor actor, UUID jobId) {
        return execute(actor, jobId, null);
    }

    @Transactional
    public JobSummary execute(CurrentActor actor, UUID jobId, String note) {
        JobAccess.requireActiveFixer(actor);
        eligibility.requireVerified(actor);
        // Lock the row: two concurrent completions must not release the escrow twice.
        var job = jobs.findByIdForUpdate(jobId).orElseThrow(JobNotFoundException::new);
        job.requireExecutedBy(actor.internalUserId());

        var completed = job.complete(note, Instant.now());
        jobs.update(completed);
        records.log(new JobLogEntry(UUID.randomUUID(), jobId, completed.status(), completed.progressNote(),
                actor.internalUserId(), completed.completedAt()));

        var request = requests.require(completed.requestId());
        requests.markCompleted(completed.requestId());
        if (request.propertyId() != null) {
            workHistory.recordCompletedWork(new PropertyWorkHistory.CompletedWork(request.propertyId(), completed.id(),
                    completed.requestId(), request.specialty().name(),
                    request.title() == null ? "Trabajo de mantenimiento" : request.title(),
                    completed.fixerUserId(), completed.completedAt()));
        }
        events.publishEvent(new JobCompleted(completed.id(), completed.quotationId(),
                completed.fixerUserId(), completed.completedAt(), completed.requestId(), completed.ownerUserId()));
        notices.progressed(completed, request, NotificationType.JOB_COMPLETED, "Trabajo terminado",
                "El técnico cerró \"" + request.title() + "\". Revisa las evidencias y califica el servicio.");
        return JobSummary.of(completed);
    }
}
