package com.fixup.audit.infrastructure;

import com.fixup.audit.api.AuditedResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "status_change_log")
class StatusChangeEntity {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private AuditedResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "previous_status", length = 64)
    private String previousStatus;

    @Column(name = "new_status", nullable = false, length = 64)
    private String newStatus;

    @Column(name = "changed_by_user_id")
    private UUID changedByUserId;

    @Column(name = "changed_by_subject", nullable = false, length = 191)
    private String changedBySubject;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected StatusChangeEntity() {
    }

    StatusChangeEntity(UUID id, AuditedResourceType resourceType, UUID resourceId,
            String previousStatus, String newStatus, UUID changedByUserId,
            String changedBySubject, Instant changedAt) {
        this.id = id;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedByUserId = changedByUserId;
        this.changedBySubject = changedBySubject;
        this.changedAt = changedAt;
    }

    UUID id() {
        return id;
    }

    AuditedResourceType resourceType() {
        return resourceType;
    }

    UUID resourceId() {
        return resourceId;
    }

    String previousStatus() {
        return previousStatus;
    }

    String newStatus() {
        return newStatus;
    }

    UUID changedByUserId() {
        return changedByUserId;
    }

    String changedBySubject() {
        return changedBySubject;
    }

    Instant changedAt() {
        return changedAt;
    }
}
