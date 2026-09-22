package com.fixup.notifications.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.notifications.api.NotificationAccessDeniedException;
import com.fixup.notifications.api.NotificationNotFoundException;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notifications;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarkNotificationAsRead {
    private final Notifications notifications;

    MarkNotificationAsRead(Notifications notifications) {
        this.notifications = notifications;
    }

    @Transactional
    public NotificationSummary execute(CurrentActor actor, UUID notificationId) {
        requireActive(actor);
        var notification = notifications.findById(notificationId)
                .orElseThrow(NotificationNotFoundException::new);
        requireOwned(notification, actor.internalUserId());
        var updated = notification.markRead(Instant.now());
        notifications.update(updated);
        return NotificationSummary.of(updated);
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new NotificationAccessDeniedException();
        }
    }

    private static void requireOwned(Notification notification, UUID userId) {
        if (!notification.userId().equals(userId)) {
            throw new NotificationAccessDeniedException();
        }
    }
}
