package com.fixup.messaging.infrastructure;

import com.fixup.notifications.api.NotificationStatus;
import com.fixup.messaging.domain.ChatMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
class ChatMessageEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "sender_user_id", nullable = false, updatable = false)
    private UUID senderUserId;
    @Column(name = "body", length = 2000, nullable = false, updatable = false)
    private String body;
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_status", nullable = false, length = 32, updatable = false)
    private NotificationStatus notificationStatus;

    protected ChatMessageEntity() {
    }

    static ChatMessageEntity from(ChatMessage message) {
        var entity = new ChatMessageEntity();
        entity.id = message.id();
        entity.requestId = message.requestId();
        entity.senderUserId = message.senderUserId();
        entity.body = message.body();
        entity.sentAt = message.sentAt();
        entity.notificationStatus = message.notificationStatus();
        return entity;
    }

    ChatMessage toDomain() {
        return new ChatMessage(id, requestId, senderUserId, body, sentAt, notificationStatus);
    }
}
