package com.fixup.notifications.domain;

import com.fixup.notifications.api.NotificationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Notifications {
    Optional<Notification> findById(UUID id);

    List<Notification> findByUser(UUID userId, boolean includeRead, int limit);

    long countUnreadByUser(UUID userId);

    void create(Notification notification);

    void createBatch(Collection<Notification> notifications);

    void update(Notification notification);

    void markAllReadForUser(UUID userId);
}
