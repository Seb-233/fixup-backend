package com.fixup.notifications.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.notifications.api.NotificationAccessDeniedException;
import com.fixup.notifications.domain.Notifications;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListMyNotifications {
    private final Notifications notifications;
    private static final int DEFAULT_LIMIT = 100;

    ListMyNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<NotificationSummary> execute(CurrentActor actor, boolean includeRead) {
        return execute(actor, includeRead, DEFAULT_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<NotificationSummary> execute(CurrentActor actor, boolean includeRead, int limit) {
        requireActive(actor);
        return notifications.findByUser(actor.internalUserId(), includeRead, limit).stream()
                .map(NotificationSummary::of)
                .toList();
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new NotificationAccessDeniedException();
        }
    }
}
