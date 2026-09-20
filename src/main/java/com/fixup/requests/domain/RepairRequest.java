package com.fixup.requests.domain;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.fixers.api.Specialty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepairRequest(UUID id, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
        RepairRequestUrgency urgency, Instant slaDeadline,
        UUID assignedFixerUserId, Instant createdAt, Instant updatedAt) {

    public static final int MAX_PHOTOS = 6;

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
        if (urgency == null) {
            urgency = RepairRequestUrgency.MEDIUM;
        }
    }

    public static RepairRequest open(UUID id, UUID ownerUserId, Specialty specialty, String title,
            String description, List<UUID> mediaIds, RepairRequestUrgency urgency, Instant now) {
        var effectiveUrgency = urgency == null ? RepairRequestUrgency.MEDIUM : urgency;
        var slaDeadline = now.plus(effectiveUrgency.slaWindow());
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.OPEN, effectiveUrgency, slaDeadline, null, now, now);
    }

    public RepairRequest assign(UUID fixerUserId, Instant now) {
        if (!isOpen()) {
            throw new RepairRequestConflictException("REQUEST_NOT_OPEN",
                    "The repair request is not open for assignment");
        }
        if (fixerUserId.equals(ownerUserId)) {
            throw new RepairRequestConflictException("SELF_ASSIGNMENT",
                    "The owner of the request cannot be assigned as its fixer");
        }
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.ASSIGNED, urgency, slaDeadline, fixerUserId, createdAt, now);
    }

    public RepairRequest startProgress(Instant now) {
        if (status != RepairRequestStatus.ASSIGNED) {
            throw new RepairRequestConflictException("REQUEST_NOT_ASSIGNED",
                    "Progress can only be started from ASSIGNED status");
        }
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.IN_PROGRESS, urgency, slaDeadline, assignedFixerUserId, createdAt, now);
    }

    public RepairRequest putOnHold(Instant now) {
        if (status != RepairRequestStatus.ASSIGNED && status != RepairRequestStatus.IN_PROGRESS) {
            throw new RepairRequestConflictException("REQUEST_NOT_ACTIVE",
                    "Hold can only be applied from ASSIGNED or IN_PROGRESS status");
        }
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.ON_HOLD, urgency, slaDeadline, assignedFixerUserId, createdAt, now);
    }

    public RepairRequest resumeFromHold(Instant now) {
        if (status != RepairRequestStatus.ON_HOLD) {
            throw new RepairRequestConflictException("REQUEST_NOT_ON_HOLD",
                    "Resume can only be applied from ON_HOLD status");
        }
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.IN_PROGRESS, urgency, slaDeadline, assignedFixerUserId, createdAt, now);
    }

    public RepairRequest complete(Instant now) {
        if (status != RepairRequestStatus.IN_PROGRESS) {
            throw new RepairRequestConflictException("REQUEST_NOT_IN_PROGRESS",
                    "Only requests in progress can be completed");
        }
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.COMPLETED, urgency, slaDeadline, assignedFixerUserId, createdAt, now);
    }

    public RepairRequest cancel(Instant now, UUID actorUserId) {
        if (!ownerUserId.equals(actorUserId)) {
            throw new RepairRequestAccessDeniedException();
        }
        if (status == RepairRequestStatus.COMPLETED || status == RepairRequestStatus.CANCELLED) {
            throw new RepairRequestConflictException("REQUEST_ALREADY_FINAL",
                    "A completed or cancelled request cannot be cancelled again");
        }
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.CANCELLED, urgency, slaDeadline,
                status == RepairRequestStatus.OPEN ? null : assignedFixerUserId, createdAt, now);
    }

    public RepairRequest withUrgency(RepairRequestUrgency newUrgency, Instant now) {
        if (status == RepairRequestStatus.COMPLETED || status == RepairRequestStatus.CANCELLED) {
            throw new RepairRequestConflictException("REQUEST_ALREADY_FINAL",
                    "Urgency cannot be changed on a completed or cancelled request");
        }
        var effectiveUrgency = newUrgency == null ? RepairRequestUrgency.MEDIUM : newUrgency;
        var newSlaDeadline = createdAt.plus(effectiveUrgency.slaWindow());
        return new RepairRequest(id, ownerUserId, specialty, title, description, mediaIds,
                status, effectiveUrgency, newSlaDeadline, assignedFixerUserId, createdAt, now);
    }

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    public boolean isTerminal() {
        return status == RepairRequestStatus.COMPLETED || status == RepairRequestStatus.CANCELLED;
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
        return new RepairRequestSnapshot(id, ownerUserId, specialty, status, urgency);
    }
}
