package com.fixup.notifications.application;

import com.fixup.notifications.api.NotificationPublisher;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notifications;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishNotification implements NotificationPublisher {
    private final Notifications notifications;

    PublishNotification(Notifications notifications) {
        this.notifications = notifications;
    }

    @Transactional
    public NotificationSummary execute(UUID userId, NotificationType type,
            String title, String message, UUID relatedEntityId) {
        var now = Instant.now();
        var notification = Notification.create(UUID.randomUUID(), userId, type,
                title, message, relatedEntityId, now);
        notifications.create(notification);
        return NotificationSummary.of(notification);
    }

    @Override
    @Transactional
    public void publish(UUID userId, NotificationType type, String title, String message,
            UUID relatedEntityId) {
        execute(userId, type, title, message, relatedEntityId);
    }

    @Transactional
    public void executeBatch(Collection<BatchItem> items) {
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

    @Override
    @Transactional
    public void publishBatch(Collection<NotificationPublisher.BatchItem> items) {
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

    public record BatchItem(UUID userId, NotificationType type, String title, String message,
            UUID relatedEntityId) {
    }
}
