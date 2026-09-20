package com.fixup.messaging.domain;

import java.util.List;
import java.util.UUID;

public interface ChatMessages {
    void save(ChatMessage message);

    /** Chronological order: oldest first, the way a chat thread reads. */
    List<ChatMessage> findByRequest(UUID requestId);
}
