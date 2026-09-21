package com.fixup.messaging.domain;

import com.fixup.notifications.api.NotificationStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A single, immutable entry in the chat tied to an assigned repair request. Never edited or deleted. */
public record ChatMessage(UUID id, UUID requestId, UUID senderUserId, String body, Instant sentAt,
        NotificationStatus notificationStatus) {

    public ChatMessage {
        Objects.requireNonNull(id);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(senderUserId);
        Objects.requireNonNull(sentAt);
        Objects.requireNonNull(notificationStatus);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A chat message requires a non-blank body");
        }
        body = body.trim();
    }

    public static ChatMessage sent(UUID id, UUID requestId, UUID senderUserId, String body, Instant now,
            NotificationStatus notificationStatus) {
        return new ChatMessage(id, requestId, senderUserId, body, now, notificationStatus);
    }
}
