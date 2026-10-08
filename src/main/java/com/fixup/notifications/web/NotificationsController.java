package com.fixup.notifications.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.application.ListNotifications;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import com.fixup.notifications.domain.UserDevices;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The notification does not exist or is not yours",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class NotificationsController {

    private final CurrentActorProvider actors;
    private final Notificaciones notificaciones;
    private final ListNotifications listNotifications;
    private final UserDevices userDevices;
    private final Clock clock;

    NotificationsController(CurrentActorProvider actors, Notificaciones notificaciones,
            UserDevices userDevices, Clock clock, ListNotifications listNotifications) {
        this.actors = actors;
        this.notificaciones = notificaciones;
        this.listNotifications = listNotifications;
        this.userDevices = userDevices;
        this.clock = clock;
    }

    @GetMapping("/me")
    @Operation(summary = "List notifications for the current user",
            description = "Returns a paginated list of the current user's notifications, newest first. "
                    + "Optionally filtered by type and/or unread-only status.")
    @ApiResponse(responseCode = "200", description = "Paginated notifications")
    NotificationPageResponse mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly) {

        UUID userId = actors.currentActor().internalUserId();
        return NotificationPageResponse.from(listNotifications.execute(userId, page, size, type, unreadOnly));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark a notification as read",
            description = "Only the recipient can mark their own notification as read.")
    @ApiResponse(responseCode = "200", description = "The notification was marked as read")
    ResponseEntity<NotificationResponse> markAsRead(@PathVariable UUID id) {
        UUID userId = actors.currentActor().internalUserId();
        boolean updated = notificaciones.markAsRead(id, userId);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return notificaciones.findByIdAndRecipientUserId(id, userId)
                .map(n -> ResponseEntity.ok(NotificationResponse.from(n)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Mark all notifications as read for the current user")
    @ApiResponse(responseCode = "200", description = "All unread notifications were marked as read")
    Map<String, Integer> markAllAsRead() {
        UUID userId = actors.currentActor().internalUserId();
        int updated = notificaciones.markAllAsRead(userId);
        return Map.of("marked", updated);
    }

    @GetMapping("/me/unread-count")
    @Operation(summary = "Get the count of unread notifications for the current user")
    @ApiResponse(responseCode = "200", description = "The count of unread notifications")
    Map<String, Long> unreadCount() {
        UUID userId = actors.currentActor().internalUserId();
        long count = notificaciones.countUnreadByRecipientUserId(userId);
        return Map.of("count", count);
    }

    @PostMapping("/device-token")
    @Operation(summary = "Register a device token for push notifications",
            description = "Associates a FCM/APNS device token with the current user for push delivery.")
    @ApiResponse(responseCode = "201", description = "Device token registered")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    DeviceTokenResponse registerDeviceToken(@Valid @RequestBody DeviceTokenRequest body) {
        UUID userId = actors.currentActor().internalUserId();
        Instant now = Instant.now(clock);
        userDevices.registerDevice(userId, body.deviceToken(), body.platform(), now);
        return new DeviceTokenResponse(userId, body.platform());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record DeviceTokenRequest(
            @NotBlank String deviceToken,
            String platform) {
    }

    record DeviceTokenResponse(UUID userId, String platform) {
    }

    @Schema(requiredProperties = {"id", "type", "title", "body", "createdAt"})
    record NotificationResponse(
            UUID id,
            NotificationType type,
            String title,
            String body,
            String navigateTo,
            UUID entityId,
            String entityType,
            Map<String, String> data,
            boolean read,
            Instant readAt,
            Instant createdAt) {

        static NotificationResponse from(Notification n) {
            return new NotificationResponse(
                    n.id(), n.type(), n.title(), n.body(), n.navigateTo(),
                    n.entityId(), n.entityType(), n.data(), n.isRead(), n.readAt(), n.createdAt());
        }
    }

    @Schema(requiredProperties = {"content", "page", "size", "totalElements", "totalPages"})
    record NotificationPageResponse(
            List<NotificationResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        static NotificationPageResponse from(ListNotifications.Result page) {
            return new NotificationPageResponse(
                    page.content().stream().map(NotificationResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }
}
