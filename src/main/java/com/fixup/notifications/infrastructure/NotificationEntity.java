package com.fixup.notifications.infrastructure;

import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
class NotificationEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private NotificationType type;
    @Column(name = "title", nullable = false, length = 200)
    private String title;
    @Column(name = "message", nullable = false, length = 2000)
    private String message;
    @Column(name = "related_entity_id")
    private UUID relatedEntityId;
    @Column(name = "is_read", nullable = false)
    private boolean read;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "read_at")
    private Instant readAt;

    protected NotificationEntity() {
    }

    static NotificationEntity from(Notification notification) {
        var entity = new NotificationEntity();
        entity.id = notification.id();
        entity.userId = notification.userId();
        entity.type = notification.type();
        entity.title = notification.title();
        entity.message = notification.message();
        entity.relatedEntityId = notification.relatedEntityId();
        entity.read = notification.read();
        entity.createdAt = notification.createdAt();
        entity.readAt = notification.readAt();
        return entity;
    }

    void apply(Notification notification) {
        read = notification.read();
        readAt = notification.readAt();
    }

    Notification toDomain() {
        return new Notification(id, userId, type, title, message, relatedEntityId,
                read, createdAt, readAt);
    }
}
