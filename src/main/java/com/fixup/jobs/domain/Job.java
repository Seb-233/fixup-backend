package com.fixup.jobs.domain;

import com.fixup.jobs.api.JobAccessDeniedException;
import com.fixup.jobs.api.JobConflictException;
import com.fixup.jobs.api.JobStatus;
import java.time.Instant;
import java.util.UUID;

/** A job preserves its agreed financial terms throughout scheduling, execution and completion. */
public record Job(UUID id, UUID requestId, UUID quotationId, UUID fixerUserId, UUID ownerUserId,
        String paymentMethod, long agreedAmount, JobStatus status, Instant createdAt, Instant completedAt, Instant updatedAt,
        Instant scheduledFor, Instant startedAt, String progressNote) {

    public Job {
        java.util.Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        if (!paymentMethod.equals("CASH") && !paymentMethod.equals("TRANSFER")) {
            throw new IllegalArgumentException("Unsupported payment method");
        }
        if (agreedAmount <= 0) {
            throw new IllegalArgumentException("agreedAmount must be positive");
        }
    }

    public Job(UUID id, UUID requestId, UUID quotationId, UUID fixerUserId, UUID ownerUserId,
            String paymentMethod, long agreedAmount, JobStatus status, Instant createdAt, Instant completedAt, Instant updatedAt) {
        this(id, requestId, quotationId, fixerUserId, ownerUserId, paymentMethod, agreedAmount, status, createdAt, completedAt, updatedAt,
                null, null, null);
    }

    public static Job assigned(UUID id, UUID requestId, UUID quotationId, UUID fixerUserId,
            UUID ownerUserId, String paymentMethod, long agreedAmount, Instant now) {
        return new Job(id, requestId, quotationId, fixerUserId, ownerUserId, paymentMethod, agreedAmount, JobStatus.ASSIGNED,
                now, null, now, null, null, null);
    }

    /** Fija o mueve la fecha acordada con el cliente. Un trabajo iniciado ya no se reprograma. */
    public Job schedule(Instant when, String note, Instant now) {
        if (status != JobStatus.ASSIGNED && status != JobStatus.SCHEDULED) {
            throw new JobConflictException("JOB_NOT_SCHEDULABLE", "Only an assigned or scheduled job can be scheduled");
        }
        if (when == null || when.isBefore(now.minusSeconds(60))) {
            throw new JobConflictException("INVALID_SCHEDULE", "The scheduled date must be in the future");
        }
        return new Job(id, requestId, quotationId, fixerUserId, ownerUserId, paymentMethod, agreedAmount, JobStatus.SCHEDULED, createdAt,
                null, now, when, startedAt, note(note));
    }

    public Job start(String note, Instant now) {
        if (status != JobStatus.ASSIGNED && status != JobStatus.SCHEDULED) {
            throw new JobConflictException("JOB_NOT_STARTABLE", "Only an assigned or scheduled job can start");
        }
        return new Job(id, requestId, quotationId, fixerUserId, ownerUserId, paymentMethod, agreedAmount, JobStatus.IN_PROGRESS, createdAt,
                null, now, scheduledFor, now, note(note));
    }

    /** Un trabajo se cierra una sola vez: de ese hecho cuelga la liberación del escrow. */
    public Job complete(Instant now) {
        return complete(null, now);
    }

    public Job complete(String note, Instant now) {
        if (status == JobStatus.COMPLETED) {
            throw new JobConflictException("JOB_ALREADY_COMPLETED", "The job is already completed");
        }
        return new Job(id, requestId, quotationId, fixerUserId, ownerUserId, paymentMethod, agreedAmount, JobStatus.COMPLETED,
                createdAt, now, now, scheduledFor, startedAt == null ? now : startedAt,
                note == null ? progressNote : note(note));
    }

    /** Solo el técnico asignado cambia el estado de su trabajo; ni el propietario ni un tercero pueden. */
    public void requireExecutedBy(UUID userId) {
        if (!fixerUserId.equals(userId)) {
            throw new JobAccessDeniedException();
        }
    }

    public boolean isOpen() {
        return status != JobStatus.COMPLETED;
    }

    private static String note(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        var trimmed = note.trim();
        if (trimmed.length() > 1000) {
            throw new JobConflictException("NOTE_TOO_LONG", "A progress note has at most 1000 characters");
        }
        return trimmed;
    }
}
