package com.fixup.notifications.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.application.GetUnreadNotificationCount;
import com.fixup.notifications.application.ListMyNotifications;
import com.fixup.notifications.application.MarkAllNotificationsAsRead;
import com.fixup.notifications.application.MarkNotificationAsRead;
import com.fixup.notifications.application.NotificationSummary;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or missing role",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "Notification not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class NotificationController {
    private final CurrentActorProvider actors;
    private final ListMyNotifications listMyNotifications;
    private final MarkNotificationAsRead markAsRead;
    private final MarkAllNotificationsAsRead markAllAsRead;
    private final GetUnreadNotificationCount unreadCount;

    NotificationController(CurrentActorProvider actors,
            ListMyNotifications listMyNotifications,
            MarkNotificationAsRead markAsRead,
            MarkAllNotificationsAsRead markAllAsRead,
            GetUnreadNotificationCount unreadCount) {
        this.actors = actors;
        this.listMyNotifications = listMyNotifications;
        this.markAsRead = markAsRead;
        this.markAllAsRead = markAllAsRead;
        this.unreadCount = unreadCount;
    }

    @GetMapping
    @Operation(summary = "List notifications for the current user",
            description = "Returns newest first. Use includeRead=true to return all notifications; default returns unread only.")
    @ApiResponse(responseCode = "200", description = "Notifications for the current user")
    List<NotificationItemResponse> list(
            @RequestParam(defaultValue = "false") boolean includeRead,
            @RequestParam(defaultValue = "100") int limit) {
        return listMyNotifications.execute(actors.currentActor(), includeRead, limit).stream()
                .map(NotificationItemResponse::of)
                .toList();
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count unread notifications for the current user")
    @ApiResponse(responseCode = "200", description = "Unread notification count")
    UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(unreadCount.execute(actors.currentActor()));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark a single notification as read")
    @ApiResponse(responseCode = "200", description = "Notification marked as read")
    NotificationItemResponse markRead(@PathVariable UUID notificationId) {
        var summary = markAsRead.execute(actors.currentActor(), notificationId);
        return NotificationItemResponse.of(summary);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all notifications for the current user as read")
    @ApiResponse(responseCode = "200", description = "All notifications marked as read")
    void markAllRead() {
        markAllAsRead.execute(actors.currentActor());
    }

    @Schema(requiredProperties = {"id", "type", "title", "read", "createdAt"})
    record NotificationItemResponse(UUID id, NotificationType type, String title,
            String message,
            @Schema(types = {"string", "null"}) UUID relatedEntityId,
            boolean read,
            Instant createdAt,
            @Schema(types = {"string", "null"}) Instant readAt) {

        static NotificationItemResponse of(NotificationSummary summary) {
            return new NotificationItemResponse(
                    summary.id(),
                    summary.type(),
                    summary.title(),
                    summary.message(),
                    summary.relatedEntityId(),
                    summary.read(),
                    summary.createdAt(),
                    summary.readAt());
        }
    }

    @Schema(requiredProperties = {"count"})
    record UnreadCountResponse(long count) {
    }
}
