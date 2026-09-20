package com.fixup.notifications.domain;

import com.fixup.notifications.api.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record Notification(
        UUID id,
        UUID userId,
        NotificationType type,
        String title,
        String message,
        UUID relatedEntityId,
        boolean read,
        Instant createdAt,
        Instant readAt) {

    public static Notification create(UUID id, UUID userId, NotificationType type,
            String title, String message, UUID relatedEntityId, Instant now) {
        return new Notification(id, userId, type, title, message, relatedEntityId, false, now, null);
    }

    public Notification markRead(Instant now) {
        if (read) {
            return this;
        }
        return new Notification(id, userId, type, title, message, relatedEntityId, true, createdAt, now);
    }
}
