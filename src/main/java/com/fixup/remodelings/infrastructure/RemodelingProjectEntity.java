package com.fixup.remodelings.infrastructure;

import com.fixup.remodelings.domain.RemodelingProject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "remodeling_projects")
class RemodelingProjectEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "property_id", nullable = false, updatable = false)
    private UUID propertyId;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;
    @Column(name = "title", nullable = false, length = 150, updatable = false)
    private String title;
    @Column(name = "description", length = 2000, updatable = false)
    private String description;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    protected RemodelingProjectEntity() {
    }

    static RemodelingProjectEntity from(RemodelingProject project) {
        var entity = new RemodelingProjectEntity();
        entity.id = project.id();
        entity.propertyId = project.propertyId();
        entity.ownerUserId = project.ownerUserId();
        entity.title = project.title();
        entity.description = project.description();
        entity.createdAt = project.createdAt();
        entity.updatedAt = project.updatedAt();
        return entity;
    }

    UUID id() {
        return id;
    }

    UUID propertyId() {
        return propertyId;
    }

    UUID ownerUserId() {
        return ownerUserId;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
