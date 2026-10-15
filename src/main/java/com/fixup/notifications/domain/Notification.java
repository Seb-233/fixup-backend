package com.fixup.notifications.domain;

import com.fixup.notifications.api.NotificationType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Notification(
        UUID id,
        UUID recipientUserId,
        NotificationType type,
        String title,
        String body,
        String navigateTo,
        UUID entityId,
        String entityType,
        Map<String, String> data,
        Instant readAt,
        Instant createdAt) {

    public static Notification create(
            UUID id,
            UUID recipientUserId,
            NotificationType type,
            String title,
            String body,
            String navigateTo,
            UUID entityId,
            String entityType,
            Map<String, String> data,
            Instant now) {
        return new Notification(id, recipientUserId, type, title, body, navigateTo,
                entityId, entityType, data, null, now);
    }

    public boolean isRead() {
        return readAt != null;
    }
}
