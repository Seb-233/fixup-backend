package com.fixup.jobs.infrastructure;

import com.fixup.jobs.api.JobStatus;
import com.fixup.jobs.domain.Job;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
class JobEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "quotation_id", nullable = false, updatable = false)
    private UUID quotationId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobEntity() {
    }

    static JobEntity from(Job job) {
        var entity = new JobEntity();
        entity.id = job.id();
        entity.requestId = job.requestId();
        entity.quotationId = job.quotationId();
        entity.fixerUserId = job.fixerUserId();
        entity.ownerUserId = job.ownerUserId();
        entity.createdAt = job.createdAt();
        entity.apply(job);
        return entity;
    }

    /** Neither party nor the originating quotation ever changes: only the outcome does. */
    void apply(Job job) {
        status = job.status();
        completedAt = job.completedAt();
        updatedAt = job.updatedAt();
    }

    Job toDomain() {
        return new Job(id, requestId, quotationId, fixerUserId, ownerUserId, status, createdAt,
                completedAt, updatedAt);
    }
}
