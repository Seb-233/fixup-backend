package com.fixup.properties.infrastructure;

import com.fixup.properties.domain.WorkRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "property_work_history")
class PropertyWorkRecordEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "property_id", nullable = false, updatable = false)
    private UUID propertyId;
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "specialty", nullable = false, length = 32, updatable = false)
    private String specialty;
    @Column(name = "title", nullable = false, length = 150, updatable = false)
    private String title;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;
    @Column(name = "rating")
    private Integer rating;

    protected PropertyWorkRecordEntity() {
    }

    static PropertyWorkRecordEntity from(WorkRecord record) {
        var entity = new PropertyWorkRecordEntity();
        entity.id = record.id();
        entity.propertyId = record.propertyId();
        entity.jobId = record.jobId();
        entity.requestId = record.requestId();
        entity.specialty = record.specialty();
        entity.title = record.title();
        entity.fixerUserId = record.fixerUserId();
        entity.completedAt = record.completedAt();
        entity.rating = record.rating();
        return entity;
    }

    WorkRecord toDomain() {
        return new WorkRecord(id, propertyId, jobId, requestId, specialty, title, fixerUserId, completedAt, rating);
    }
}
