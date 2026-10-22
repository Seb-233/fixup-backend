package com.fixup.jobs.infrastructure;

import com.fixup.jobs.api.JobStatus;
import com.fixup.jobs.domain.JobLogEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_status_log")
class JobLogEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32, updatable = false)
    private JobStatus status;
    @Column(name = "note", length = 1000, updatable = false)
    private String note;
    @Column(name = "changed_by_user_id", nullable = false, updatable = false)
    private UUID changedByUserId;
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected JobLogEntity() {
    }

    static JobLogEntity from(JobLogEntry entry) {
        var entity = new JobLogEntity();
        entity.id = entry.id();
        entity.jobId = entry.jobId();
        entity.status = entry.status();
        entity.note = entry.note();
        entity.changedByUserId = entry.changedByUserId();
        entity.changedAt = entry.changedAt();
        return entity;
    }

    JobLogEntry toDomain() {
        return new JobLogEntry(id, jobId, status, note, changedByUserId, changedAt);
    }
}
