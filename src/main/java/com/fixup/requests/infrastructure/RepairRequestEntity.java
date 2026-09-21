package com.fixup.requests.infrastructure;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "repair_requests")
class RepairRequestEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "property_id", updatable = false)
    private UUID propertyId;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false, length = 32)
    private Specialty specialty;
    @Column(name = "title", nullable = false, length = 150)
    private String title;
    @Column(name = "description", nullable = false, length = 2000)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RepairRequestStatus status;
    @Column(name = "assigned_fixer_user_id")
    private UUID assignedFixerUserId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "repair_request_photos", joinColumns = @JoinColumn(name = "request_id"))
    @OrderColumn(name = "photo_order")
    @Column(name = "media_id", nullable = false)
    private List<UUID> mediaIds = new ArrayList<>();

    protected RepairRequestEntity() {
    }

    static RepairRequestEntity from(RepairRequest request) {
        var entity = new RepairRequestEntity();
        entity.id = request.id();
        entity.propertyId = request.propertyId();
        entity.ownerUserId = request.ownerUserId();
        entity.specialty = request.specialty();
        entity.title = request.title();
        entity.description = request.description();
        entity.mediaIds = new ArrayList<>(request.mediaIds());
        entity.createdAt = request.createdAt();
        entity.apply(request);
        return entity;
    }

    /** Only the mutable part of the request travels back: ownership and photos never change. */
    void apply(RepairRequest request) {
        status = request.status();
        assignedFixerUserId = request.assignedFixerUserId();
        updatedAt = request.updatedAt();
    }

    RepairRequest toDomain() {
        return new RepairRequest(id, propertyId, ownerUserId, specialty, title, description, List.copyOf(mediaIds),
                status, assignedFixerUserId, createdAt, updatedAt);
    }
}
