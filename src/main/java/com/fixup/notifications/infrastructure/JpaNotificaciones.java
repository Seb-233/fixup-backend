package com.fixup.notifications.infrastructure;

import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaNotificaciones implements Notificaciones {

    private final NotificationJpaRepository repository;
    private final Clock clock;

    JpaNotificaciones(NotificationJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Notification save(Notification notification) {
        return repository.saveAndFlush(NotificationEntity.from(notification)).toDomain();
    }

    @Override
    public Optional<Notification> findByIdAndRecipientUserId(UUID id, UUID recipientUserId) {
        return repository.findById(id)
                .filter(e -> e.getRecipientUserId().equals(recipientUserId))
                .map(NotificationEntity::toDomain);
    }

    @Override
    public Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable) {
        return repository.findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId, pageable)
                .map(NotificationEntity::toDomain);
    }

    @Override
    public Page<Notification> findByRecipientUserIdAndTypeOrderByCreatedAtDesc(UUID recipientUserId, NotificationType type, Pageable pageable) {
        return repository.findByRecipientUserIdAndTypeOrderByCreatedAtDesc(recipientUserId, type, pageable)
                .map(NotificationEntity::toDomain);
    }

    @Override
    public Page<Notification> findUnreadByRecipientUserId(UUID userId, Pageable pageable) {
        return repository.findUnreadByRecipientUserId(userId, pageable)
                .map(NotificationEntity::toDomain);
    }

    @Override
    public Page<Notification> findUnreadByRecipientUserIdAndType(UUID userId, NotificationType type, Pageable pageable) {
        return repository.findUnreadByRecipientUserIdAndType(userId, type, pageable)
                .map(NotificationEntity::toDomain);
    }

    @Override
    public long countUnreadByRecipientUserId(UUID recipientUserId) {
        return repository.countByRecipientUserIdAndReadAtIsNull(recipientUserId);
    }

    @Override
    @Transactional
    public boolean markAsRead(UUID notifId, UUID userId) {
        return repository.markAsRead(notifId, userId, Instant.now(clock)) > 0;
    }

    @Override
    @Transactional
    public int markAllAsRead(UUID userId) {
        return repository.markAllAsRead(userId, Instant.now(clock));
    }
}
