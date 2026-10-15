package com.fixup.notifications.application;

import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ListNotifications {
    private final Notificaciones notifications;

    public ListNotifications(Notificaciones notifications) {
        this.notifications = notifications;
    }

    public Result execute(UUID userId, int page, int size, NotificationType type, boolean unreadOnly) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        var result = unreadOnly
                ? (type == null ? notifications.findUnreadByRecipientUserId(userId, pageable)
                        : notifications.findUnreadByRecipientUserIdAndType(userId, type, pageable))
                : (type == null ? notifications.findByRecipientUserIdOrderByCreatedAtDesc(userId, pageable)
                        : notifications.findByRecipientUserIdAndTypeOrderByCreatedAtDesc(userId, type, pageable));
        return new Result(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public record Result(List<Notification> content, int page, int size, long totalElements, int totalPages) {}
}
