package com.fixup.notifications.infrastructure;

import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notifications;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
class JpaNotifications implements Notifications {
    private final NotificationJpaRepository repository;

    JpaNotifications(NotificationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        return repository.findById(id).map(NotificationEntity::toDomain);
    }

    @Override
    public List<Notification> findByUser(UUID userId, boolean includeRead, int limit) {
        var safeLimit = Math.max(1, Math.min(limit, 500));
        return repository.findByUserIdOrderByCreatedAtDesc(userId, includeRead,
                        PageRequest.of(0, safeLimit))
                .stream()
                .map(NotificationEntity::toDomain)
                .toList();
    }

    @Override
    public long countUnreadByUser(UUID userId) {
        return repository.countByUserIdAndReadIsFalse(userId);
    }

    @Override
    public void create(Notification notification) {
        repository.saveAndFlush(NotificationEntity.from(notification));
    }

    @Override
    public void createBatch(Collection<Notification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        var entities = new ArrayList<NotificationEntity>(notifications.size());
        for (var n : notifications) {
            entities.add(NotificationEntity.from(n));
        }
        repository.saveAllAndFlush(entities);
    }

    @Override
    public void update(Notification notification) {
        var entity = repository.findById(notification.id())
                .orElseThrow(() -> new com.fixup.notifications.api.NotificationNotFoundException());
        entity.apply(notification);
        repository.saveAndFlush(entity);
    }

    @Override
    public void markAllReadForUser(UUID userId) {
        repository.markAllReadForUser(userId, Instant.now());
    }
}
