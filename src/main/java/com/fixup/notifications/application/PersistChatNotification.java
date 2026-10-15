package com.fixup.notifications.application;

import com.fixup.notifications.api.ChatNotifications;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class PersistChatNotification implements ChatNotifications {
    private final Notificaciones notifications;

    PersistChatNotification(Notificaciones notifications) {
        this.notifications = notifications;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void messageReceived(UUID messageId, UUID requestId, UUID senderId,
            UUID recipientId, String preview, Instant occurredAt) {
        notifications.save(Notification.create(UUID.randomUUID(), recipientId,
                NotificationType.CHAT_MESSAGE_RECEIVED, "Nuevo mensaje", preview,
                "/requests/" + requestId + "/chat", messageId, "CHAT_MESSAGE",
                Map.of("requestId", requestId.toString(), "senderId", senderId.toString()),
                occurredAt));
    }
}
