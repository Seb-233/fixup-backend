package com.fixup.jobs.api;

import java.time.Instant;
import java.util.UUID;

/** Immutable job view used by project-level read models, ratings and payments. */
public record JobSnapshot(UUID requestId, UUID jobId, UUID fixerUserId, JobStatus status, UUID ownerUserId,
        UUID quotationId, Instant completedAt) {

    public JobSnapshot(UUID requestId, UUID jobId, UUID fixerUserId, JobStatus status) {
        this(requestId, jobId, fixerUserId, status, null, null, null);
    }
}
