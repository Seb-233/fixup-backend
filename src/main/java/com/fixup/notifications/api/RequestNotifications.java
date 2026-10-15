package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

public interface RequestNotifications {
    void urgentRequestCreated(UUID requestId, UUID ownerUserId, String title, Instant occurredAt);
}
