package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import com.fixup.notifications.api.NotificationType;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-19: el técnico mueve su trabajo por los estados intermedios. Programar fija la fecha
 * acordada con el cliente; iniciar marca que ya está en el inmueble ejecutándolo.
 */
@Service
public class ProgressJob {
    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("EEEE d 'de' MMMM, h:mm a", Locale.forLanguageTag("es-CO"))
            .withZone(ZoneId.of("America/Bogota"));

    private final Jobs jobs;
    private final JobRecords records;
    private final FixerEligibility eligibility;
    private final RepairRequestDirectory requests;
    private final JobNotices notices;

    ProgressJob(Jobs jobs, JobRecords records, FixerEligibility eligibility, RepairRequestDirectory requests,
            JobNotices notices) {
        this.jobs = jobs;
        this.records = records;
        this.eligibility = eligibility;
        this.requests = requests;
        this.notices = notices;
    }

    @Transactional
    public JobSummary schedule(CurrentActor actor, UUID jobId, Instant when, String note) {
        var job = lockOwnJob(actor, jobId);
        var now = Instant.now();
        var scheduled = job.schedule(when, note, now);
        jobs.update(scheduled);
        records.log(new JobLogEntry(UUID.randomUUID(), jobId, scheduled.status(), scheduled.progressNote(),
                actor.internalUserId(), now));
        var request = requests.require(job.requestId());
        notices.progressed(scheduled, request, NotificationType.JOB_STATUS_CHANGED, "Trabajo programado",
                "El técnico agendó \"" + request.title() + "\" para el " + WHEN.format(when) + ".");
        return JobSummary.of(scheduled);
    }

    @Transactional
    public JobSummary start(CurrentActor actor, UUID jobId, String note) {
        var job = lockOwnJob(actor, jobId);
        var now = Instant.now();
        var started = job.start(note, now);
        jobs.update(started);
        records.log(new JobLogEntry(UUID.randomUUID(), jobId, started.status(), started.progressNote(),
                actor.internalUserId(), now));
        var request = requests.require(job.requestId());
        notices.progressed(started, request, NotificationType.JOB_STATUS_CHANGED, "Trabajo en ejecución",
                "El técnico empezó a trabajar en \"" + request.title() + "\".");
        return JobSummary.of(started);
    }

    private com.fixup.jobs.domain.Job lockOwnJob(CurrentActor actor, UUID jobId) {
        JobAccess.requireActiveFixer(actor);
        eligibility.requireVerified(actor);
        var job = jobs.findByIdForUpdate(jobId).orElseThrow(JobNotFoundException::new);
        job.requireExecutedBy(actor.internalUserId());
        return job;
    }
}
