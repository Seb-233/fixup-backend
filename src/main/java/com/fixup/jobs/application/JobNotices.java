package com.fixup.jobs.application;

import com.fixup.jobs.domain.Job;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.requests.api.RepairRequestSnapshot;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** FR-UC-10 y FR-UC-19: cada avance del trabajo se le avisa a quienes esperan por él. */
@Component
class JobNotices {
    private final ApplicationEventPublisher events;

    JobNotices(ApplicationEventPublisher events) {
        this.events = events;
    }

    void progressed(Job job, RepairRequestSnapshot request, NotificationType type, String title, String body) {
        for (UUID recipient : request.participants()) {
            if (recipient.equals(job.fixerUserId())) {
                continue;
            }
            events.publishEvent(NotificationRequested.of(recipient, type, title, body,
                    "/requests/" + job.requestId(), job.id(), "JOB"));
        }
    }
}
