package com.fixup.jobs.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.JobEvidence;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.requests.api.RepairRequestDirectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-19: el estado, la bitácora y las evidencias de un trabajo, para el técnico y para el cliente. */
@Service
public class GetJobDetail {
    private final Jobs jobs;
    private final JobRecords records;
    private final RepairRequestDirectory requests;
    private final MediaAttachmentService media;
    private final UserDirectory users;

    GetJobDetail(Jobs jobs, JobRecords records, RepairRequestDirectory requests, MediaAttachmentService media,
            UserDirectory users) {
        this.jobs = jobs;
        this.records = records;
        this.requests = requests;
        this.media = media;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public JobDetail byId(CurrentActor actor, UUID jobId) {
        return detail(actor, jobs.findById(jobId).orElseThrow(JobNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public JobDetail byRequest(CurrentActor actor, UUID requestId) {
        return detail(actor, jobs.findByRequestId(requestId).orElseThrow(JobNotFoundException::new));
    }

    private JobDetail detail(CurrentActor actor, Job job) {
        var request = requests.require(job.requestId());
        JobAccess.requireCanRead(actor, job, request);
        var evidence = records.evidenceOf(job.id());
        var urls = new HashMap<UUID, String>();
        media.resolveReadUrls(evidence.stream().map(JobEvidence::mediaId).toList())
                .forEach(view -> urls.put(view.mediaId(), view.readUrl()));
        var log = records.logOf(job.id());
        var people = new ArrayList<UUID>();
        people.add(job.fixerUserId());
        people.add(job.ownerUserId());
        log.forEach(entry -> people.add(entry.changedByUserId()));
        var names = users.displayNames(people);
        return new JobDetail(JobSummary.of(job), request.title(),
                request.specialty() == null ? null : request.specialty().name(), request.propertyName(),
                request.propertyAddress(),
                request.propertyCity(), names.get(job.fixerUserId()), names.get(job.ownerUserId()),
                evidence.stream().map(item -> new JobDetail.Evidence(item.id(), item.mediaId(), urls.get(item.mediaId()),
                        item.stage(), item.caption(), item.createdAt())).toList(),
                log.stream().map(entry -> new JobDetail.LogEntry(entry.status(), entry.note(),
                        names.get(entry.changedByUserId()), entry.changedAt())).toList());
    }

    public record JobDetail(JobSummary job, String requestTitle, String specialty, String propertyName,
            String propertyAddress,
            String propertyCity, String fixerName, String ownerName, List<Evidence> evidence, List<LogEntry> log) {

        public record Evidence(UUID id, UUID mediaId, String url, JobEvidence.Stage stage, String caption,
                Instant createdAt) {
        }

        public record LogEntry(com.fixup.jobs.api.JobStatus status, String note, String changedBy, Instant changedAt) {
        }
    }
}
