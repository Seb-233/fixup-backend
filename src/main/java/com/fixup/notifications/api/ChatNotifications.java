package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Persist a recipient-visible chat signal inside the message transaction. */
public interface ChatNotifications {
    void messageReceived(UUID messageId, UUID requestId, UUID senderId,
            UUID recipientId, String preview, Instant occurredAt);
}
