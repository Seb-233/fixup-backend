package com.fixup.notifications.application;

import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import java.time.Instant;
import java.util.UUID;

public record NotificationSummary(
        UUID id,
        NotificationType type,
        String title,
        String message,
        UUID relatedEntityId,
        boolean read,
        Instant createdAt,
        Instant readAt) {

    static NotificationSummary of(Notification notification) {
        return new NotificationSummary(
                notification.id(),
                notification.type(),
                notification.title(),
                notification.message(),
                notification.relatedEntityId(),
                notification.read(),
                notification.createdAt(),
                notification.readAt());
    }
}
