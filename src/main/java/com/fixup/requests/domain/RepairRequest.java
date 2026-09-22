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

/**
 * FR-UC-18: la solicitud que el Fixer evalúa antes de cotizar. Las fotografías se guardan como
 * storage keys; el contenido nunca cruza la API.
 *
 * <p>FR-UC-04 aporta el inmueble contra el que se abre la solicitud, que es lo que permite
 * resolver la autorización del propietario sobre el registro. FR-UC-08 aporta la urgencia, el
 * plazo de SLA que se deriva de ella y los estados por los que pasa el trabajo. Las dos cosas
 * conviven: el inmueble dice de quién es la solicitud y la urgencia dice para cuándo.
 */
public record RepairRequest(UUID id, UUID propertyId, UUID ownerUserId, Specialty specialty, String title,
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

    public static RepairRequest open(UUID id, UUID propertyId, UUID ownerUserId, Specialty specialty, String title,
            String description, List<UUID> mediaIds, RepairRequestUrgency urgency, Instant now) {
        if (propertyId == null) {
            throw new RepairRequestConflictException("INVALID_PROPERTY",
                    "A propertyId is required to open a new repair request");
        }
        var effectiveUrgency = urgency == null ? RepairRequestUrgency.MEDIUM : urgency;
        return new RepairRequest(id, propertyId, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.OPEN, effectiveUrgency, now.plus(effectiveUrgency.slaWindow()),
                null, now, now);
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
        return withStatus(RepairRequestStatus.ASSIGNED, fixerUserId, now);
    }

    public RepairRequest startProgress(Instant now) {
        if (status != RepairRequestStatus.ASSIGNED) {
            throw new RepairRequestConflictException("REQUEST_NOT_ASSIGNED",
                    "Progress can only be started from ASSIGNED status");
        }
        return withStatus(RepairRequestStatus.IN_PROGRESS, assignedFixerUserId, now);
    }

    public RepairRequest putOnHold(Instant now) {
        if (status != RepairRequestStatus.ASSIGNED && status != RepairRequestStatus.IN_PROGRESS) {
            throw new RepairRequestConflictException("REQUEST_NOT_ACTIVE",
                    "Hold can only be applied from ASSIGNED or IN_PROGRESS status");
        }
        return withStatus(RepairRequestStatus.ON_HOLD, assignedFixerUserId, now);
    }

    public RepairRequest resumeFromHold(Instant now) {
        if (status != RepairRequestStatus.ON_HOLD) {
            throw new RepairRequestConflictException("REQUEST_NOT_ON_HOLD",
                    "Resume can only be applied from ON_HOLD status");
        }
        return withStatus(RepairRequestStatus.IN_PROGRESS, assignedFixerUserId, now);
    }

    /**
     * FR-UC-08 + FR-UC-20: la solicitud se da por terminada cuando el técnico cierra el trabajo y
     * se libera el dinero retenido, no por una ruta propia. Por eso este método no lo invoca un
     * controlador sino el escucha de JobCompleted: un único cierre, y el escrow nunca queda
     * retenido sobre una solicitud que ya nadie va a atender.
     */
    public RepairRequest complete(Instant now) {
        if (status != RepairRequestStatus.ASSIGNED && status != RepairRequestStatus.IN_PROGRESS
                && status != RepairRequestStatus.ON_HOLD) {
            throw new RepairRequestConflictException("REQUEST_NOT_ACTIVE",
                    "Only an assigned, in-progress or on-hold request can be completed");
        }
        return withStatus(RepairRequestStatus.COMPLETED, assignedFixerUserId, now);
    }

    public RepairRequest cancel(Instant now, UUID actorUserId) {
        if (!ownerUserId.equals(actorUserId)) {
            throw new RepairRequestAccessDeniedException();
        }
        if (isTerminal()) {
            throw new RepairRequestConflictException("REQUEST_ALREADY_FINAL",
                    "A completed or cancelled request cannot be cancelled again");
        }
        return withStatus(RepairRequestStatus.CANCELLED,
                status == RepairRequestStatus.OPEN ? null : assignedFixerUserId, now);
    }

    /**
     * FR-UC-08: cambiar la urgencia mueve el plazo, y lo mueve desde la apertura de la solicitud y
     * no desde ahora: el compromiso es "esta reparación se atiende en N horas desde que se pidió".
     * Subir la urgencia de una solicitud vieja puede dejarla vencida de inmediato, y eso es lo
     * correcto: significa que ya se debía haber atendido con ese criterio.
     */
    public RepairRequest withUrgency(RepairRequestUrgency newUrgency, Instant now) {
        if (isTerminal()) {
            throw new RepairRequestConflictException("REQUEST_ALREADY_FINAL",
                    "Urgency cannot be changed on a completed or cancelled request");
        }
        var effectiveUrgency = newUrgency == null ? RepairRequestUrgency.MEDIUM : newUrgency;
        return new RepairRequest(id, propertyId, ownerUserId, specialty, title, description, mediaIds,
                status, effectiveUrgency, createdAt.plus(effectiveUrgency.slaWindow()),
                assignedFixerUserId, createdAt, now);
    }

    private RepairRequest withStatus(RepairRequestStatus newStatus, UUID fixerUserId, Instant now) {
        return new RepairRequest(id, propertyId, ownerUserId, specialty, title, description, mediaIds,
                newStatus, urgency, slaDeadline, fixerUserId, createdAt, now);
    }

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    public boolean isTerminal() {
        return status == RepairRequestStatus.COMPLETED || status == RepairRequestStatus.CANCELLED;
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
        return new RepairRequestSnapshot(id, ownerUserId, specialty, status, assignedFixerUserId, urgency);
    }
}
