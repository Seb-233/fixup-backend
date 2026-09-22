package com.fixup.notifications.application;

import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notifications;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-10: escribe el aviso. Lo invoca NotificationEventListener al reaccionar a los hechos de
 * los demás módulos; ningún módulo llama aquí directamente, que es lo que mantiene a notifications
 * en el extremo receptor de las dependencias.
 */
@org.springframework.stereotype.Service
class PublishNotification {
    private final Notifications notifications;

    PublishNotification(Notifications notifications) {
        this.notifications = notifications;
    }

    @Transactional
    NotificationSummary execute(UUID userId, NotificationType type,
            String title, String message, UUID relatedEntityId) {
        var now = Instant.now();
        var notification = Notification.create(UUID.randomUUID(), userId, type,
                title, message, relatedEntityId, now);
        notifications.create(notification);
        return NotificationSummary.of(notification);
    }

    @Transactional
    void executeBatch(Collection<BatchItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        var now = Instant.now();
        List<Notification> batch = items.stream()
                .map(item -> Notification.create(UUID.randomUUID(), item.userId(), item.type(),
                        item.title(), item.message(), item.relatedEntityId(), now))
                .toList();
        notifications.createBatch(batch);
    }

    record BatchItem(UUID userId, NotificationType type, String title, String message,
            UUID relatedEntityId) {
    }
}
