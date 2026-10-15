package com.fixup.notifications.infrastructure;

import com.fixup.notifications.api.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notifications")
class NotificationEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32, updatable = false)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, length = 2000, updatable = false)
    private String body;

    @Column(name = "navigate_to", length = 500, updatable = false)
    private String navigateTo;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @Column(name = "entity_type", length = 64, updatable = false)
    private String entityType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb", updatable = false)
    private Map<String, String> data;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationEntity() {
    }

    static NotificationEntity from(com.fixup.notifications.domain.Notification notification) {
        var entity = new NotificationEntity();
        entity.id = notification.id();
        entity.recipientUserId = notification.recipientUserId();
        entity.type = notification.type();
        entity.title = notification.title();
        entity.body = notification.body();
        entity.navigateTo = notification.navigateTo();
        entity.entityId = notification.entityId();
        entity.entityType = notification.entityType();
        entity.data = notification.data() != null ? new HashMap<>(notification.data()) : null;
        entity.readAt = notification.readAt();
        entity.createdAt = notification.createdAt();
        return entity;
    }

    com.fixup.notifications.domain.Notification toDomain() {
        return new com.fixup.notifications.domain.Notification(
                id, recipientUserId, type, title, body, navigateTo,
                entityId, entityType, data, readAt, createdAt);
    }

    void markAsRead(Instant when) {
        this.readAt = when;
    }

    UUID getId() {
        return id;
    }

    UUID getRecipientUserId() {
        return recipientUserId;
    }
}
