package com.fixup.jobs.domain;

import com.fixup.jobs.api.JobAccessDeniedException;
import com.fixup.jobs.api.JobConflictException;
import com.fixup.jobs.api.JobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: el trabajo nace cuando el propietario acepta una cotización y termina cuando el
 * técnico lo cierra. Guarda a las dos partes porque el dinero se mueve entre ellas.
 */
public record Job(UUID id, UUID requestId, UUID quotationId, UUID fixerUserId, UUID ownerUserId,
        JobStatus status, Instant createdAt, Instant completedAt, Instant updatedAt) {

    public static Job assigned(UUID id, UUID requestId, UUID quotationId, UUID fixerUserId,
            UUID ownerUserId, Instant now) {
        return new Job(id, requestId, quotationId, fixerUserId, ownerUserId, JobStatus.ASSIGNED,
                now, null, now);
    }

    /** Un trabajo se cierra una sola vez: de ese hecho cuelga la liberación del escrow. */
    public Job complete(Instant now) {
        if (status == JobStatus.COMPLETED) {
            throw new JobConflictException("JOB_ALREADY_COMPLETED", "The job is already completed");
        }
        return new Job(id, requestId, quotationId, fixerUserId, ownerUserId, JobStatus.COMPLETED,
                createdAt, now, now);
    }

    /** Solo el técnico asignado cierra su trabajo; ni el propietario ni un tercero pueden. */
    public void requireExecutedBy(UUID userId) {
        if (!fixerUserId.equals(userId)) {
            throw new JobAccessDeniedException();
        }
    }
}
