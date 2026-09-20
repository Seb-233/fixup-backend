package com.fixup.jobs.application;

import com.fixup.jobs.api.JobStatus;
import com.fixup.jobs.domain.Job;
import java.time.Instant;
import java.util.UUID;

/** Read model of a job as the fixer executing it sees it. */
public record JobSummary(UUID id, UUID requestId, UUID quotationId, UUID ownerUserId, JobStatus status,
        Instant createdAt, Instant completedAt) {

    static JobSummary of(Job job) {
        return new JobSummary(job.id(), job.requestId(), job.quotationId(), job.ownerUserId(),
                job.status(), job.createdAt(), job.completedAt());
    }
}
