package com.fixup.jobs.infrastructure;

import com.fixup.jobs.domain.JobEvidence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_evidences")
class JobEvidenceEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;
    @Column(name = "media_id", nullable = false, updatable = false)
    private UUID mediaId;
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16, updatable = false)
    private JobEvidence.Stage stage;
    @Column(name = "caption", length = 200, updatable = false)
    private String caption;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected JobEvidenceEntity() {
    }

    static JobEvidenceEntity from(JobEvidence evidence) {
        var entity = new JobEvidenceEntity();
        entity.id = evidence.id();
        entity.jobId = evidence.jobId();
        entity.mediaId = evidence.mediaId();
        entity.stage = evidence.stage();
        entity.caption = evidence.caption();
        entity.createdAt = evidence.createdAt();
        return entity;
    }

    JobEvidence toDomain() {
        return new JobEvidence(id, jobId, mediaId, stage, caption, createdAt);
    }
}
