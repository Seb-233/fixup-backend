package com.fixup.visits.application;

import com.fixup.notifications.api.NotificationType;
import com.fixup.visits.domain.Visits;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-03 y RF-03: recordatorio a todas las partes cuando falta un día o menos para la visita. */
@Component
class VisitReminderScheduler {
    static final Duration LOOKAHEAD = Duration.ofHours(24);

    private final Visits visits;
    private final VisitNotices notices;
    private final Clock clock;

    VisitReminderScheduler(Visits visits, VisitNotices notices, Clock clock) {
        this.visits = visits;
        this.notices = notices;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${fixup.visits.reminder-delay:PT10M}", initialDelayString = "PT1M")
    @Transactional
    public void remind() {
        var now = Instant.now(clock);
        for (var visit : visits.findDueForReminder(now, now.plus(LOOKAHEAD))) {
            visits.update(visit.markReminderSent(now));
            notices.send(visit.participants(), null, visit, NotificationType.VISIT_REMINDER, "Recordatorio de visita",
                    ScheduleVisit.label(visit.kind()) + " el " + VisitNotices.when(visit)
                            + (visit.addressSnapshot() == null ? "." : " en " + visit.addressSnapshot() + "."));
        }
    }
}
