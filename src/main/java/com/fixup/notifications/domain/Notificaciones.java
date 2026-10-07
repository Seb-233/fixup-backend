package com.fixup.notifications.domain;

import com.fixup.notifications.api.NotificationType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface Notificaciones {

    Notification save(Notification notification);

    Optional<Notification> findByIdAndRecipientUserId(UUID id, UUID recipientUserId);

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndTypeOrderByCreatedAtDesc(UUID recipientUserId, NotificationType type, Pageable pageable);

    Page<Notification> findUnreadByRecipientUserId(UUID userId, Pageable pageable);

    Page<Notification> findUnreadByRecipientUserIdAndType(UUID userId, NotificationType type, Pageable pageable);

    long countUnreadByRecipientUserId(UUID recipientUserId);

    boolean markAsRead(UUID notifId, UUID userId);

    int markAllAsRead(UUID userId);
}
