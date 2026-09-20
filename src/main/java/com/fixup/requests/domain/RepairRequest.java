package com.fixup.requests.domain;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-18: la solicitud que el Fixer evalÃºa antes de cotizar. Las fotografÃ­as se guardan como
 * storage keys; el contenido nunca cruza la API.
 */
public record RepairRequest(UUID id, UUID propertyId, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
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
    }

    public static RepairRequest open(UUID id, UUID propertyId, UUID ownerUserId, Specialty specialty, String title,
            String description, List<UUID> mediaIds, Instant now) {
        if (propertyId == null) {
            throw new RepairRequestConflictException("INVALID_PROPERTY",
                    "A propertyId is required to open a new repair request");
        }
        return new RepairRequest(id, propertyId, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.OPEN, null, now, now);
    }

    /** A request leaves OPEN exactly once: the accepted quotation closes it for everyone else. */
    public RepairRequest assign(UUID fixerUserId, Instant now) {
        if (!isOpen()) {
            throw new RepairRequestConflictException("REQUEST_NOT_OPEN",
                    "The repair request is already assigned");
        }
        if (fixerUserId.equals(ownerUserId)) {
            throw new RepairRequestConflictException("SELF_ASSIGNMENT",
                    "The owner of the request cannot be assigned as its fixer");
        }
        return new RepairRequest(id, propertyId, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.ASSIGNED, fixerUserId, createdAt, now);
    }

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    /**
     * FR-UC-25: la autorizaciÃ³n se resuelve sobre el registro, no sobre la pantalla. El dueÃ±o
     * siempre lo ve; un Fixer ve la solicitud mientras estÃ¡ en oferta, porque necesita la
     * descripciÃ³n y las fotos para cotizar, y despuÃ©s solo si el trabajo quedÃ³ asignado a Ã©l.
     * Un identificador adivinado no alcanza para leer la solicitud de otro.
     */
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
}
