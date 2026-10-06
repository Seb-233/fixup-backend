package com.fixup.requests.domain;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.fixers.api.Specialty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepairRequest(UUID id, UUID propertyId, String propertyCity, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
        UUID assignedFixerUserId, Instant createdAt, Instant updatedAt,
        UrgencyLevel urgencyLevel, Instant slaDeadline, Instant lastEscalationNotifiedAt) {

    public static final int MAX_PHOTOS = 6;
    private static final Duration URGENT_SLA_DURATION = Duration.ofHours(48);

    public RepairRequest {
        if (mediaIds == null) {
            mediaIds = List.of();
        } else {
            for (UUID mediaId : mediaIds) {
                if (mediaId == null) {
                    throw new RepairRequestConflictException("INVALID_PHOTOS",
                            "Photo mediaIds cannot contain null elements");
                }
            }
            if (new java.util.HashSet<>(mediaIds).size() != mediaIds.size()) {
                throw new RepairRequestConflictException("DUPLICATE_PHOTOS",
                        "Photo mediaIds cannot contain duplicates");
            }
            mediaIds = List.copyOf(mediaIds);
        }
        if (mediaIds.size() > MAX_PHOTOS) {
            throw new RepairRequestConflictException("TOO_MANY_PHOTOS",
                    "A repair request accepts at most " + MAX_PHOTOS + " photos");
        }
    }

    public static RepairRequest open(UUID id, UUID propertyId, String propertyCity, UUID ownerUserId, Specialty specialty, String title,
            String description, List<UUID> mediaIds, UrgencyLevel urgencyLevel, Instant now) {
        if (propertyId == null) {
            throw new RepairRequestConflictException("INVALID_PROPERTY",
                    "A propertyId is required to open a new repair request");
        }
        UrgencyLevel effectiveUrgency = urgencyLevel == null ? UrgencyLevel.MEDIUM : urgencyLevel;
        Instant slaDeadline = UrgencyLevel.URGENT.equals(effectiveUrgency)
                ? now.plus(URGENT_SLA_DURATION)
                : null;
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.OPEN, null, now, now, effectiveUrgency, slaDeadline, null);
    }

    public RepairRequest assign(UUID fixerUserId, Instant now) {
        if (!isOpen()) {
            throw new RepairRequestConflictException("REQUEST_NOT_OPEN",
                    "The repair request is already assigned");
        }
        if (fixerUserId.equals(ownerUserId)) {
            throw new RepairRequestConflictException("SELF_ASSIGNMENT",
                    "The owner of the request cannot be assigned as its fixer");
        }
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.ASSIGNED, fixerUserId, createdAt, now,
                urgencyLevel, slaDeadline, lastEscalationNotifiedAt);
    }

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    public void requireVisibleTo(CurrentActor actor) {
        var userId = actor.internalUserId();
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RepairRequestAccessDeniedException();
        }
        if (ownerUserId.equals(userId) || userId.equals(assignedFixerUserId)
                || actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        if (!actor.hasRole(Role.FIXER) || !isOpen()) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    public void requireOwnedBy(UUID userId) {
        if (!ownerUserId.equals(userId)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    public RepairRequestSnapshot snapshot() {
        return new RepairRequestSnapshot(id, ownerUserId, specialty, status, assignedFixerUserId);
    }

    public RepairRequest withStatus(RepairRequestStatus newStatus, Instant now) {
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                newStatus, assignedFixerUserId, createdAt, now,
                urgencyLevel, slaDeadline, lastEscalationNotifiedAt);
    }

    public RepairRequest withLastEscalationNotifiedAt(Instant now) {
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                status, assignedFixerUserId, createdAt, now,
                urgencyLevel, slaDeadline, now);
    }

    public RepairRequest withAssignedFixerUserId(UUID fixerUserId, Instant now) {
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                status, fixerUserId, createdAt, now,
                urgencyLevel, slaDeadline, lastEscalationNotifiedAt);
    }
}
