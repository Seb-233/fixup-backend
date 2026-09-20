package com.fixup.notifications.api;

import java.util.UUID;

public record NotificationSnapshot(
        UUID id,
        UUID userId,
        NotificationType type,
        String title,
        String message,
        UUID relatedEntityId,
        boolean read) {
}
