package com.fixup.visits.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.notifications.api.NotificationType;
import com.fixup.visits.api.VisitAccessDeniedException;
import com.fixup.visits.api.VisitConflictException;
import com.fixup.visits.api.VisitNotFoundException;
import com.fixup.visits.domain.Visit;
import com.fixup.visits.domain.Visits;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-03 y FR-UC-27: confirmar, reprogramar, cancelar y cerrar una visita. */
@Service
public class DecideVisit {
    private final Visits visits;
    private final VisitNotices notices;

    DecideVisit(Visits visits, VisitNotices notices) {
        this.visits = visits;
        this.notices = notices;
    }

    @Transactional
    public Visit confirm(CurrentActor actor, UUID visitId) {
        var visit = lock(actor, visitId);
        // The fixer's own agenda is fully visible only to the fixer (RLS hides other clients' visits), so
        // the hard double-booking check happens when the fixer confirms.
        if (visit.fixerUserId().equals(actor.internalUserId())) {
            requireFreeSlot(visit.fixerUserId(), visitId, visit.scheduledAt(), visit.durationMinutes());
        }
        var confirmed = visit.confirm(actor.internalUserId(), Instant.now());
        visits.update(confirmed);
        notices.send(confirmed.participants(), actor.internalUserId(), confirmed, NotificationType.VISIT_CONFIRMED,
                "Visita confirmada", "La visita quedó para el " + VisitNotices.when(confirmed) + ".");
        return confirmed;
    }

    @Transactional
    public Visit reschedule(CurrentActor actor, UUID visitId, Instant scheduledAt, int durationMinutes) {
        var visit = lock(actor, visitId);
        requireFreeSlot(visit.fixerUserId(), visitId, scheduledAt, durationMinutes);
        var moved = visit.reschedule(actor.internalUserId(), scheduledAt, durationMinutes, Instant.now());
        visits.update(moved);
        notices.send(moved.participants(), actor.internalUserId(), moved, NotificationType.VISIT_RESCHEDULED,
                "Propusieron otra hora para la visita",
                "Nueva propuesta: " + VisitNotices.when(moved) + ". Confírmala si te sirve.");
        return moved;
    }

    @Transactional
    public Visit cancel(CurrentActor actor, UUID visitId, String reason) {
        var visit = lock(actor, visitId);
        var cancelled = visit.cancel(actor.internalUserId(), reason, Instant.now());
        visits.update(cancelled);
        notices.send(cancelled.participants(), actor.internalUserId(), cancelled, NotificationType.VISIT_CANCELLED,
                "Visita cancelada", "Se canceló la visita del " + VisitNotices.when(cancelled) + ". Motivo: "
                        + cancelled.cancelReason());
        return cancelled;
    }

    @Transactional
    public Visit complete(CurrentActor actor, UUID visitId, String outcome, Map<Integer, Visit.ChecklistEntry> checklist) {
        var visit = lock(actor, visitId);
        var completed = visit.complete(actor.internalUserId(), outcome, checklist, Instant.now());
        visits.update(completed);
        notices.send(completed.participants(), actor.internalUserId(), completed, NotificationType.VISIT_CONFIRMED,
                "El técnico registró el resultado de la visita", completed.outcome());
        return completed;
    }

    @Transactional
    public Visit noShow(CurrentActor actor, UUID visitId) {
        var visit = lock(actor, visitId);
        var missed = visit.markNoShow(actor.internalUserId(), Instant.now());
        visits.update(missed);
        notices.send(missed.participants(), actor.internalUserId(), missed, NotificationType.VISIT_CANCELLED,
                "Visita no realizada", "La visita del " + VisitNotices.when(missed) + " se marcó como no realizada.");
        return missed;
    }

    private void requireFreeSlot(UUID fixerUserId, UUID visitId, Instant start, int minutes) {
        var busy = visits.findActiveForFixerBetween(fixerUserId, start.minus(Duration.ofHours(4)),
                        start.plus(Duration.ofHours(4))).stream()
                .filter(other -> !other.id().equals(visitId))
                .filter(other -> other.status() == com.fixup.visits.api.VisitStatus.CONFIRMED)
                .anyMatch(other -> other.overlaps(start, minutes));
        if (busy) {
            throw new VisitConflictException("FIXER_BUSY", "The fixer already has a confirmed visit at that time");
        }
    }

    private Visit lock(CurrentActor actor, UUID visitId) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new VisitAccessDeniedException();
        }
        var visit = visits.findByIdForUpdate(visitId).orElseThrow(VisitNotFoundException::new);
        if (!visit.isParticipant(actor.internalUserId())) {
            throw new VisitNotFoundException();
        }
        return visit;
    }
}
