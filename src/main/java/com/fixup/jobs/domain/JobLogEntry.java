package com.fixup.jobs.domain;

import com.fixup.jobs.api.JobStatus;
import java.time.Instant;
import java.util.UUID;

/** FR-UC-19: cada cambio de estado del trabajo, quién lo hizo y con qué nota. */
public record JobLogEntry(UUID id, UUID jobId, JobStatus status, String note, UUID changedByUserId,
        Instant changedAt) {
}
