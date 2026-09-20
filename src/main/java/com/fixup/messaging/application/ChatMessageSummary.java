package com.fixup.messaging.application;

import com.fixup.messaging.api.NotificationStatus;
import com.fixup.messaging.domain.ChatMessage;
import java.time.Instant;
import java.util.UUID;

/** Read model of a chat message, identical for both participants: there is nothing to hide. */
public record ChatMessageSummary(UUID id, UUID requestId, UUID senderUserId, String body, Instant sentAt,
        NotificationStatus notificationStatus) {

    static ChatMessageSummary of(ChatMessage message) {
        return new ChatMessageSummary(message.id(), message.requestId(), message.senderUserId(),
                message.body(), message.sentAt(), message.notificationStatus());
    }
}
