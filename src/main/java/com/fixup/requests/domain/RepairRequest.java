package com.fixup.requests.domain;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.PaymentMethod;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.fixers.api.Specialty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Una solicitud de reparación. {@code ownerUserId} es siempre el propietario del inmueble: quien
 * compara, acepta y paga. {@code requestedByUserId} es quien reportó la falla (el propietario, su
 * arrendatario o su administrador) y {@code managerUserId} el administrador inmobiliario que
 * gestiona el inmueble, si lo hay (FR-UC-08, FR-UC-11).
 */
public record RepairRequest(UUID id, UUID propertyId, String propertyCity, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
        UUID assignedFixerUserId, PaymentMethod paymentMethod, Instant createdAt, Instant updatedAt,
        UrgencyLevel urgencyLevel, Instant slaDeadline, Instant lastEscalationNotifiedAt,
        UUID requestedByUserId, UUID managerUserId, String propertyName, String propertyAddress,
        Instant completedAt) {

    public static final int MAX_PHOTOS = 6;
    private static final Duration URGENT_SLA_DURATION = Duration.ofHours(48);
    private static final Set<RepairRequestStatus> CLOSED = Set.of(RepairRequestStatus.COMPLETED,
            RepairRequestStatus.CANCELLED);

    public RepairRequest {
        if (paymentMethod == null) {
            throw new RepairRequestConflictException("PAYMENT_METHOD_REQUIRED",
                    "A payment method is required to open a repair request");
        }
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
        if (requestedByUserId == null) {
            requestedByUserId = ownerUserId;
        }
    }

    public RepairRequest(UUID id, UUID propertyId, String propertyCity, UUID ownerUserId, Specialty specialty,
            String title, String description, List<UUID> mediaIds, RepairRequestStatus status,
            UUID assignedFixerUserId, PaymentMethod paymentMethod, Instant createdAt, Instant updatedAt,
            UrgencyLevel urgencyLevel, Instant slaDeadline, Instant lastEscalationNotifiedAt) {
        this(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds, status,
                assignedFixerUserId, paymentMethod, createdAt, updatedAt, urgencyLevel, slaDeadline, lastEscalationNotifiedAt,
                ownerUserId, null, null, null, null);
    }

    public static RepairRequest open(UUID id, UUID propertyId, String propertyCity, UUID ownerUserId, Specialty specialty, String title,
            String description, List<UUID> mediaIds, UrgencyLevel urgencyLevel, PaymentMethod paymentMethod, Instant now) {
        return open(id, new PropertyContext(propertyId, propertyCity, null, null, ownerUserId, null), ownerUserId,
                specialty, title, description, mediaIds, urgencyLevel, paymentMethod, now);
    }

    /** FR-UC-04 y FR-UC-08: abre la solicitud con el contexto del inmueble y quien la reporta. */
    public static RepairRequest open(UUID id, PropertyContext property, UUID requestedByUserId, Specialty specialty,
            String title, String description, List<UUID> mediaIds, UrgencyLevel urgencyLevel, PaymentMethod paymentMethod, Instant now) {
        if (property == null || property.propertyId() == null) {
            throw new RepairRequestConflictException("INVALID_PROPERTY",
                    "A propertyId is required to open a new repair request");
        }
        UrgencyLevel effectiveUrgency = urgencyLevel == null ? UrgencyLevel.MEDIUM : urgencyLevel;
        Instant slaDeadline = UrgencyLevel.URGENT.equals(effectiveUrgency)
                ? now.plus(URGENT_SLA_DURATION)
                : null;
        return new RepairRequest(id, property.propertyId(), property.city(), property.ownerUserId(), specialty, title,
                description, mediaIds, RepairRequestStatus.OPEN, null, paymentMethod, now, now, effectiveUrgency, slaDeadline, null,
                requestedByUserId, property.managerUserId(), property.name(), property.address(), null);
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
                RepairRequestStatus.ASSIGNED, fixerUserId, paymentMethod, createdAt, now,
                urgencyLevel, slaDeadline, lastEscalationNotifiedAt,
                requestedByUserId, managerUserId, propertyName, propertyAddress, completedAt);
    }

    /** FR-UC-19: cerrar el trabajo registra también el cierre de la solicitud. */
    public RepairRequest complete(Instant now) {
        if (assignedFixerUserId == null || CLOSED.contains(status)) {
            throw new RepairRequestConflictException("REQUEST_NOT_ASSIGNED",
                    "Only an assigned request that is still active can be completed");
        }
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                RepairRequestStatus.COMPLETED, assignedFixerUserId, paymentMethod, createdAt, now,
                urgencyLevel, slaDeadline, lastEscalationNotifiedAt,
                requestedByUserId, managerUserId, propertyName, propertyAddress, now);
    }

    /** Quien la abrió o decide sobre el inmueble puede retirarla mientras nadie la ha tomado. */
    public RepairRequest cancel(Instant now) {
        if (!isOpen()) {
            throw new RepairRequestConflictException("REQUEST_NOT_OPEN",
                    "Only an open request can be cancelled");
        }
        return withStatus(RepairRequestStatus.CANCELLED, now);
    }

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    /** Hay un técnico asignado y la solicitud no está cerrada (incluye los estados de alerta de SLA). */
    public boolean isActiveWithFixer() {
        return assignedFixerUserId != null && !CLOSED.contains(status);
    }

    /** FR-UC-11: el propietario y el administrador del inmueble deciden cotizaciones y técnicos. */
    public boolean isDecidedBy(UUID userId) {
        return ownerUserId.equals(userId) || (managerUserId != null && managerUserId.equals(userId));
    }

    /** Los que ven la solicitud por relación con el inmueble, sin contar al técnico. */
    public boolean involves(UUID userId) {
        return isDecidedBy(userId) || (requestedByUserId != null && requestedByUserId.equals(userId));
    }

    public void requireVisibleTo(CurrentActor actor) {
        var userId = actor.internalUserId();
        if (actor.status() != UserStatus.ACTIVE) {
            throw new RepairRequestAccessDeniedException();
        }
        if (involves(userId) || userId.equals(assignedFixerUserId)
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
        return new RepairRequestSnapshot(id, ownerUserId, specialty, status, assignedFixerUserId, paymentMethod, propertyId,
                requestedByUserId, managerUserId, title, propertyName, propertyAddress, propertyCity);
    }

    public RepairRequest withStatus(RepairRequestStatus newStatus, Instant now) {
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                newStatus, assignedFixerUserId, paymentMethod, createdAt, now,
                urgencyLevel, slaDeadline, lastEscalationNotifiedAt,
                requestedByUserId, managerUserId, propertyName, propertyAddress, completedAt);
    }

    public RepairRequest withLastEscalationNotifiedAt(Instant now) {
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                status, assignedFixerUserId, paymentMethod, createdAt, now,
                urgencyLevel, slaDeadline, now,
                requestedByUserId, managerUserId, propertyName, propertyAddress, completedAt);
    }

    public RepairRequest withAssignedFixerUserId(UUID fixerUserId, Instant now) {
        return new RepairRequest(id, propertyId, propertyCity, ownerUserId, specialty, title, description, mediaIds,
                status, fixerUserId, paymentMethod, createdAt, now,
                urgencyLevel, slaDeadline, lastEscalationNotifiedAt,
                requestedByUserId, managerUserId, propertyName, propertyAddress, completedAt);
    }

    /** Lo que la solicitud copia del inmueble al abrirse, para no depender de él al leerla después. */
    public record PropertyContext(UUID propertyId, String city, String name, String address, UUID ownerUserId,
            UUID managerUserId) {
    }
}
