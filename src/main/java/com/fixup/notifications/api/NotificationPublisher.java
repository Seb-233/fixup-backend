package com.fixup.notifications.api;

import java.util.Collection;
import java.util.UUID;

/**
 * Public API of the notifications module: callers from other modules must depend on this interface
 * instead of internal application/infrastructure classes. It dispatches notifications inside the
 * same transaction; persistence happens when the caller's transaction commits.
 */
public interface NotificationPublisher {

    void publish(UUID userId, NotificationType type, String title, String message, UUID relatedEntityId);

    void publishBatch(Collection<BatchItem> items);

    record BatchItem(UUID userId, NotificationType type, String title, String message,
            UUID relatedEntityId) {
    }
}
