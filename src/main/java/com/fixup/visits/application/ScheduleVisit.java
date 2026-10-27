package com.fixup.visits.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.notifications.api.NotificationType;
import com.fixup.quotations.api.QuotationDirectory;
import com.fixup.requests.api.RepairRequestDirectory;
import com.fixup.requests.api.RepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.visits.api.VisitAccessDeniedException;
import com.fixup.visits.api.VisitConflictException;
import com.fixup.visits.api.VisitKind;
import com.fixup.visits.domain.Visit;
import com.fixup.visits.domain.Visits;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-03: agenda una visita técnica sobre una solicitud. La propone quien tiene relación con el
 * inmueble o el propio técnico. Con la solicitud abierta solo cabe una visita de diagnóstico o de
 * valoración de cocina, y solo con un técnico que ya cotizó; la visita de ejecución exige que el
 * técnico esté asignado.
 */
@Service
public class ScheduleVisit {
    private final Visits visits;
    private final RepairRequestDirectory requests;
    private final QuotationDirectory quotations;
    private final VisitNotices notices;

    ScheduleVisit(Visits visits, RepairRequestDirectory requests, QuotationDirectory quotations, VisitNotices notices) {
        this.visits = visits;
        this.requests = requests;
        this.quotations = quotations;
        this.notices = notices;
    }

    @Transactional
    public Visit execute(CurrentActor actor, UUID requestId, UUID fixerUserId, VisitKind kind, Instant scheduledAt,
            int durationMinutes, String notes) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new VisitAccessDeniedException();
        }
        var request = requests.require(requestId);
        if (request.status() == RepairRequestStatus.COMPLETED || request.status() == RepairRequestStatus.CANCELLED) {
            throw new VisitConflictException("REQUEST_CLOSED", "The request is already closed");
        }
        var actorId = actor.internalUserId();
        UUID fixer;
        if (actor.hasRole(Role.FIXER) && !request.isParticipant(actorId)) {
            fixer = actorId;
        } else if (actor.hasRole(Role.FIXER) && actorId.equals(request.assignedFixerUserId())) {
            fixer = actorId;
        } else if (request.isParticipant(actorId)) {
            fixer = fixerUserId != null ? fixerUserId : request.assignedFixerUserId();
        } else {
            throw new VisitAccessDeniedException();
        }
        if (fixer == null) {
            throw new VisitConflictException("FIXER_REQUIRED", "Choose the fixer who will visit");
        }
        requireEligibleFixer(request, fixer, kind);

        var now = Instant.now();
        // Only the fixer sees their whole agenda (RLS hides other clients' visits), so the double-booking
        // check runs when the fixer proposes here and, for a client's proposal, when the fixer confirms it.
        var overlapping = fixer.equals(actorId) && visits.findActiveForFixerBetween(fixer,
                        scheduledAt.minus(Duration.ofHours(4)), scheduledAt.plus(Duration.ofHours(4))).stream()
                .filter(other -> other.status() == com.fixup.visits.api.VisitStatus.CONFIRMED)
                .anyMatch(other -> other.overlaps(scheduledAt, durationMinutes));
        if (overlapping) {
            throw new VisitConflictException("FIXER_BUSY", "The fixer already has a visit at that time");
        }
        var visit = Visit.propose(UUID.randomUUID(), new Visit.Parties(request.requestId(), request.propertyId(),
                        request.ownerUserId(), request.requestedByUserId(), request.managerUserId(), fixer),
                actorId, kind, scheduledAt, durationMinutes, request.propertyAddress(), notes, now);
        visits.create(visit);
        notices.send(visit.counterparts(), actorId, visit, NotificationType.VISIT_PROPOSED,
                "Te propusieron una visita técnica",
                label(kind) + " para \"" + request.title() + "\" el " + VisitNotices.when(visit) + ". Confírmala o propón otra hora.");
        return visit;
    }

    private void requireEligibleFixer(RepairRequestSnapshot request, UUID fixer, VisitKind kind) {
        if (fixer.equals(request.ownerUserId())) {
            throw new VisitConflictException("SELF_VISIT", "The owner cannot be the visiting fixer");
        }
        if (fixer.equals(request.assignedFixerUserId())) {
            return;
        }
        if (kind == VisitKind.EXECUTION) {
            throw new VisitConflictException("FIXER_NOT_ASSIGNED", "An execution visit needs the assigned fixer");
        }
        if (!request.isOpen() || !quotations.fixersWhoQuoted(request.requestId()).contains(fixer)) {
            throw new VisitConflictException("FIXER_NOT_QUOTED",
                    "A diagnostic visit is scheduled with a fixer who already quoted the open request");
        }
    }

    static String label(VisitKind kind) {
        return switch (kind) {
            case DIAGNOSTIC -> "Visita de diagnóstico";
            case EXECUTION -> "Visita de ejecución";
            case KITCHEN_ASSESSMENT -> "Valoración de cocina";
        };
    }
}
