package com.fixup.notifications.api;


import java.util.UUID;

/**
 * Extension point for real push delivery (FCM or otherwise). Returns an explicit
 * {@link NotificationStatus} instead of relying on exceptions as control flow:
 *
 * <ul>
 *   <li>{@code SENT}: provider confirmed the notification was queued for delivery.
 *   <li>{@code SKIPPED}: no provider is configured; notification intentionally omitted.
 *   <li>{@code FAILED}: a configured provider was attempted but could not deliver.
 * </ul>
 *
 * Callers must always treat delivery as best-effort and never let the outcome block the operation
 * that triggered the notification.
 */
public interface PushNotificationGateway {

    /**
     * Attempts to deliver a push notification.
     *
     * @return the outcome; never {@code null}.
     * @throws RuntimeException only for unexpected infrastructure failures outside the provider
     *     contract (e.g. misconfigured dependency injection). Normal delivery failures must be
     *     returned as {@link NotificationStatus#FAILED}, not thrown.
     */
    NotificationStatus send(PushNotification notification);

    record PushNotification(UUID recipientUserId, String title, String body) {
    }
}