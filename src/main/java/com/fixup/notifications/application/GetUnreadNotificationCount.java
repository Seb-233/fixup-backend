package com.fixup.notifications.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.notifications.api.NotificationAccessDeniedException;
import com.fixup.notifications.domain.Notifications;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetUnreadNotificationCount {
    private final Notifications notifications;

    GetUnreadNotificationCount(Notifications notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public long execute(CurrentActor actor) {
        requireActive(actor);
        return notifications.countUnreadByUser(actor.internalUserId());
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new NotificationAccessDeniedException();
        }
    }
}
