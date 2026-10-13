package com.fixup.visits.domain;

import com.fixup.visits.api.ChecklistResult;
import com.fixup.visits.api.VisitAccessDeniedException;
import com.fixup.visits.api.VisitConflictException;
import com.fixup.visits.api.VisitKind;
import com.fixup.visits.api.VisitStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-UC-03: una visita técnica la propone una de las partes y la confirma la otra. Reprogramar es
 * proponer otra fecha: la visita vuelve a PROPOSED y ahora confirma quien no reprogramó. Solo el
 * técnico la da por realizada, con su diagnóstico; FR-UC-27 agrega el checklist de cocina.
 */
public record Visit(UUID id, UUID requestId, UUID propertyId, UUID ownerUserId, UUID requestedByUserId,
        UUID managerUserId, UUID fixerUserId, UUID proposedByUserId, VisitKind kind, VisitStatus status,
        Instant scheduledAt, int durationMinutes, String addressSnapshot, String notes, String cancelReason,
        String outcome, Instant reminderSentAt, Instant completedAt, Instant createdAt, Instant updatedAt,
        List<ChecklistItem> checklist) {

    public static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");
    static final LocalTime EARLIEST = LocalTime.of(7, 0);
    static final LocalTime LATEST_END = LocalTime.of(19, 0);
    static final Duration MINIMUM_NOTICE = Duration.ofHours(2);

    public Visit {
        checklist = checklist == null ? List.of() : List.copyOf(checklist);
    }

    public static Visit propose(UUID id, Parties parties, UUID proposedBy, VisitKind kind, Instant scheduledAt,
            int durationMinutes, String addressSnapshot, String notes, Instant now) {
        if (kind == null) {
            throw new VisitConflictException("INVALID_KIND", "The visit kind is required");
        }
        requireSlot(scheduledAt, durationMinutes, now);
        var checklist = kind == VisitKind.KITCHEN_ASSESSMENT ? KitchenChecklist.items() : List.<ChecklistItem>of();
        return new Visit(id, parties.requestId(), parties.propertyId(), parties.ownerUserId(),
                parties.requestedByUserId(), parties.managerUserId(), parties.fixerUserId(), proposedBy, kind,
                VisitStatus.PROPOSED, scheduledAt, durationMinutes, addressSnapshot, text(notes, 1000), null, null,
                null, null, now, now, checklist);
    }

    /** Confirma la contraparte de quien propuso: quien propone no se confirma a sí mismo. */
    public Visit confirm(UUID actor, Instant now) {
        requireParticipant(actor);
        if (status != VisitStatus.PROPOSED) {
            throw new VisitConflictException("VISIT_NOT_PROPOSED", "Only a proposed visit can be confirmed");
        }
        if (!counterparts().contains(actor)) {
            throw new VisitConflictException("SELF_CONFIRMATION", "The other party must confirm the proposed date");
        }
        if (scheduledAt.isBefore(now)) {
            throw new VisitConflictException("VISIT_IN_THE_PAST", "The proposed date already passed; propose another one");
        }
        return with(VisitStatus.CONFIRMED, proposedByUserId, scheduledAt, durationMinutes, cancelReason, outcome,
                completedAt, checklist, now);
    }

    public Visit reschedule(UUID actor, Instant newDate, int newDuration, Instant now) {
        requireParticipant(actor);
        if (status != VisitStatus.PROPOSED && status != VisitStatus.CONFIRMED) {
            throw new VisitConflictException("VISIT_CLOSED", "A closed visit cannot be rescheduled");
        }
        requireSlot(newDate, newDuration, now);
        return new Visit(id, requestId, propertyId, ownerUserId, requestedByUserId, managerUserId, fixerUserId, actor,
                kind, VisitStatus.PROPOSED, newDate, newDuration, addressSnapshot, notes, null, null, null, null,
                createdAt, now, checklist);
    }

    public Visit cancel(UUID actor, String reason, Instant now) {
        requireParticipant(actor);
        if (status != VisitStatus.PROPOSED && status != VisitStatus.CONFIRMED) {
            throw new VisitConflictException("VISIT_CLOSED", "The visit is already closed");
        }
        var why = text(reason, 500);
        if (why == null) {
            throw new VisitConflictException("REASON_REQUIRED", "Tell the other party why the visit is cancelled");
        }
        return with(VisitStatus.CANCELLED, proposedByUserId, scheduledAt, durationMinutes, why, outcome, null,
                checklist, now);
    }

    /** El técnico registra el resultado; en la variante cocina, el resultado de cada frente. */
    public Visit complete(UUID actor, String outcomeText, Map<Integer, ChecklistEntry> answers, Instant now) {
        if (!fixerUserId.equals(actor)) {
            throw new VisitAccessDeniedException();
        }
        if (status != VisitStatus.CONFIRMED) {
            throw new VisitConflictException("VISIT_NOT_CONFIRMED", "Only a confirmed visit can be completed");
        }
        if (now.isBefore(scheduledAt)) {
            throw new VisitConflictException("VISIT_NOT_STARTED", "A visit is completed after it starts");
        }
        var summary = text(outcomeText, 2000);
        if (summary == null) {
            throw new VisitConflictException("OUTCOME_REQUIRED", "Describe what the visit found");
        }
        var answered = new ArrayList<ChecklistItem>();
        for (ChecklistItem item : checklist) {
            var answer = answers == null ? null : answers.get(item.position());
            if (answer == null || answer.result() == null) {
                throw new VisitConflictException("CHECKLIST_INCOMPLETE",
                        "Every checklist item needs a result: " + item.label());
            }
            answered.add(new ChecklistItem(item.position(), item.label(), answer.result(), text(answer.note(), 500)));
        }
        return with(VisitStatus.COMPLETED, proposedByUserId, scheduledAt, durationMinutes, cancelReason, summary, now,
                answered, now);
    }

    public Visit markNoShow(UUID actor, Instant now) {
        requireParticipant(actor);
        if (status != VisitStatus.CONFIRMED) {
            throw new VisitConflictException("VISIT_NOT_CONFIRMED", "Only a confirmed visit can be marked as missed");
        }
        if (now.isBefore(scheduledAt)) {
            throw new VisitConflictException("VISIT_NOT_STARTED", "A visit is missed only after its start time");
        }
        return with(VisitStatus.NO_SHOW, proposedByUserId, scheduledAt, durationMinutes, cancelReason, outcome, null,
                checklist, now);
    }

    public Visit markReminderSent(Instant now) {
        return new Visit(id, requestId, propertyId, ownerUserId, requestedByUserId, managerUserId, fixerUserId,
                proposedByUserId, kind, status, scheduledAt, durationMinutes, addressSnapshot, notes, cancelReason,
                outcome, now, completedAt, createdAt, now, checklist);
    }

    public Instant endsAt() {
        return scheduledAt.plus(Duration.ofMinutes(durationMinutes));
    }

    public boolean overlaps(Instant start, int minutes) {
        var end = start.plus(Duration.ofMinutes(minutes));
        return start.isBefore(endsAt()) && scheduledAt.isBefore(end);
    }

    public Set<UUID> participants() {
        var people = new LinkedHashSet<UUID>();
        people.add(ownerUserId);
        if (requestedByUserId != null) {
            people.add(requestedByUserId);
        }
        if (managerUserId != null) {
            people.add(managerUserId);
        }
        people.add(fixerUserId);
        return Set.copyOf(people);
    }

    public boolean isParticipant(UUID userId) {
        return userId != null && participants().contains(userId);
    }

    /** Quien no propuso: a quien hay que pedirle confirmación. */
    public Set<UUID> counterparts() {
        var people = new LinkedHashSet<>(participants());
        people.remove(proposedByUserId);
        if (!fixerUserId.equals(proposedByUserId)) {
            return Set.of(fixerUserId);
        }
        return Set.copyOf(people);
    }

    private void requireParticipant(UUID actor) {
        if (!isParticipant(actor)) {
            throw new VisitAccessDeniedException();
        }
    }

    private Visit with(VisitStatus newStatus, UUID proposer, Instant date, int minutes, String reason, String result,
            Instant doneAt, List<ChecklistItem> items, Instant now) {
        return new Visit(id, requestId, propertyId, ownerUserId, requestedByUserId, managerUserId, fixerUserId, proposer,
                kind, newStatus, date, minutes, addressSnapshot, notes, reason, result, reminderSentAt, doneAt,
                createdAt, now, items);
    }

    /**
     * Horario de visita en Colombia: entre 7:00 y 19:00, con al menos dos horas de anticipación para
     * que la contraparte alcance a confirmar.
     */
    static void requireSlot(Instant start, int minutes, Instant now) {
        if (start == null) {
            throw new VisitConflictException("INVALID_DATE", "The visit date is required");
        }
        if (minutes < 30 || minutes > 240) {
            throw new VisitConflictException("INVALID_DURATION", "A visit lasts between 30 and 240 minutes");
        }
        if (start.isBefore(now.plus(MINIMUM_NOTICE))) {
            throw new VisitConflictException("TOO_SOON", "Schedule the visit at least two hours ahead");
        }
        var localStart = start.atZone(COLOMBIA).toLocalTime();
        var localEnd = start.plus(Duration.ofMinutes(minutes)).atZone(COLOMBIA).toLocalTime();
        if (localStart.isBefore(EARLIEST) || localEnd.isAfter(LATEST_END) || localEnd.isBefore(localStart)) {
            throw new VisitConflictException("OUTSIDE_HOURS", "Visits happen between 7:00 and 19:00, Colombia time");
        }
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new VisitConflictException("TEXT_TOO_LONG", "Text must be at most " + max + " characters");
        }
        return trimmed;
    }

    public record Parties(UUID requestId, UUID propertyId, UUID ownerUserId, UUID requestedByUserId,
            UUID managerUserId, UUID fixerUserId) {
    }

    public record ChecklistItem(int position, String label, ChecklistResult result, String note) {
    }

    public record ChecklistEntry(ChecklistResult result, String note) {
    }
}
