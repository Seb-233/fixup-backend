package com.fixup.visits.application;

import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.visits.domain.Visit;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** FR-UC-03 y FR-UC-10: avisos de la agenda de visitas, con la fecha en hora de Colombia. */
@Component
class VisitNotices {
    static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("EEEE d 'de' MMMM 'a las' h:mm a", Locale.forLanguageTag("es-CO"))
            .withZone(Visit.COLOMBIA);

    private final ApplicationEventPublisher events;

    VisitNotices(ApplicationEventPublisher events) {
        this.events = events;
    }

    void send(Collection<UUID> recipients, UUID except, Visit visit, NotificationType type, String title, String body) {
        for (UUID recipient : recipients) {
            if (recipient.equals(except)) {
                continue;
            }
            events.publishEvent(NotificationRequested.of(recipient, type, title, body, "/visits/" + visit.id(),
                    visit.id(), "VISIT"));
        }
    }

    static String when(Visit visit) {
        return WHEN.format(visit.scheduledAt());
    }
}
