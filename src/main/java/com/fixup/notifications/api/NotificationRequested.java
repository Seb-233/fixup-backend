package com.fixup.notifications.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * FR-UC-10: cualquier módulo pide avisarle algo a un usuario publicando este evento dentro de su
 * propia transacción. notifications lo guarda después del commit: si el caso de uso se revierte,
 * el aviso nunca existió. Los módulos productores dependen de esta api y no al revés, así que
 * notifications sigue sin conocer requests, quotations ni jobs.
 */
public record NotificationRequested(UUID recipientUserId, NotificationType type, String title, String body,
        String navigateTo, UUID entityId, String entityType, Map<String, String> data) {

    public NotificationRequested {
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static NotificationRequested of(UUID recipientUserId, NotificationType type, String title,
            String body, String navigateTo, UUID entityId, String entityType) {
        return new NotificationRequested(recipientUserId, type, title, body, navigateTo, entityId, entityType,
                Map.of());
    }
}
