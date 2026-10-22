package com.fixup.visits.infrastructure;

import com.fixup.visits.api.VisitKind;
import com.fixup.visits.api.VisitStatus;
import com.fixup.visits.domain.Visit;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "technical_visits")
class VisitEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "property_id", updatable = false)
    private UUID propertyId;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;
    @Column(name = "requested_by_user_id", updatable = false)
    private UUID requestedByUserId;
    @Column(name = "manager_user_id", updatable = false)
    private UUID managerUserId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "proposed_by_user_id", nullable = false)
    private UUID proposedByUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24, updatable = false)
    private VisitKind kind;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private VisitStatus status;
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
    @Column(name = "address_snapshot", length = 255, updatable = false)
    private String addressSnapshot;
    @Column(name = "notes", length = 1000, updatable = false)
    private String notes;
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
    @Column(name = "outcome", length = 2000)
    private String outcome;
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "visit_checklist_items", joinColumns = @JoinColumn(name = "visit_id"))
    @OrderBy("position ASC")
    private List<ChecklistItemEmbeddable> checklist = new ArrayList<>();

    protected VisitEntity() {
    }

    static VisitEntity from(Visit visit) {
        var entity = new VisitEntity();
        entity.id = visit.id();
        entity.requestId = visit.requestId();
        entity.propertyId = visit.propertyId();
        entity.ownerUserId = visit.ownerUserId();
        entity.requestedByUserId = visit.requestedByUserId();
        entity.managerUserId = visit.managerUserId();
        entity.fixerUserId = visit.fixerUserId();
        entity.kind = visit.kind();
        entity.addressSnapshot = visit.addressSnapshot();
        entity.notes = visit.notes();
        entity.createdAt = visit.createdAt();
        entity.apply(visit);
        return entity;
    }

    void apply(Visit visit) {
        proposedByUserId = visit.proposedByUserId();
        status = visit.status();
        scheduledAt = visit.scheduledAt();
        durationMinutes = visit.durationMinutes();
        cancelReason = visit.cancelReason();
        outcome = visit.outcome();
        reminderSentAt = visit.reminderSentAt();
        completedAt = visit.completedAt();
        updatedAt = visit.updatedAt();
        checklist = new ArrayList<>(visit.checklist().stream()
                .map(item -> new ChecklistItemEmbeddable(item.position(), item.label(), item.result(), item.note()))
                .toList());
    }

    Visit toDomain() {
        return new Visit(id, requestId, propertyId, ownerUserId, requestedByUserId, managerUserId, fixerUserId,
                proposedByUserId, kind, status, scheduledAt, durationMinutes, addressSnapshot, notes, cancelReason,
                outcome, reminderSentAt, completedAt, createdAt, updatedAt,
                checklist.stream().sorted(Comparator.comparingInt(ChecklistItemEmbeddable::position))
                        .map(item -> new Visit.ChecklistItem(item.position(), item.label(), item.result(), item.note()))
                        .toList());
    }
}
