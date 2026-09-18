package com.fixup.requests.domain;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.Specialty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-18: la solicitud que el Fixer evalúa antes de cotizar. Las fotografías se guardan como
 * storage keys; el contenido nunca cruza la API.
 */
public record RepairRequest(UUID id, UUID ownerUserId, Specialty specialty, String title,
        String description, List<String> photoKeys, RepairRequestStatus status,
        UUID assignedFixerUserId, Instant createdAt, Instant updatedAt) {

    public static final int MAX_PHOTOS = 6;

    public RepairRequest {
        photoKeys = List.copyOf(photoKeys);
        if (photoKeys.size() > MAX_PHOTOS) {
            throw new RepairRequestConflictException("TOO_MANY_PHOTOS",
                    "A repair request accepts at most " + MAX_PHOTOS + " photos");
        }
    }

    public static RepairRequest open(UUID id, UUID ownerUserId, Specialty specialty, String title,
            String description, List<String> photoKeys, Instant now) {
        return new RepairRequest(id, ownerUserId, specialty, title, description, photoKeys,
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
        return new RepairRequest(id, ownerUserId, specialty, title, description, photoKeys,
                RepairRequestStatus.ASSIGNED, fixerUserId, createdAt, now);
    }

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    /**
     * FR-UC-25: la autorización se resuelve sobre el registro, no sobre la pantalla. El dueño
     * siempre lo ve; un Fixer ve la solicitud mientras está en oferta, porque necesita la
     * descripción y las fotos para cotizar, y después solo si el trabajo quedó asignado a él.
     * Un identificador adivinado no alcanza para leer la solicitud de otro.
     */
    public void requireVisibleTo(CurrentActor actor) {
        var userId = actor.internalUserId();
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RepairRequestAccessDeniedException();
        }
        if (ownerUserId.equals(userId) || userId.equals(assignedFixerUserId)) {
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
        return new RepairRequestSnapshot(id, ownerUserId, specialty, status);
    }
}
