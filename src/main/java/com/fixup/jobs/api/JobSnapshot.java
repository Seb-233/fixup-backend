package com.fixup.jobs.api;

import java.util.UUID;

/** Immutable job view used by project-level read models. */
public record JobSnapshot(UUID requestId, UUID jobId, UUID fixerUserId, JobStatus status) {
}
