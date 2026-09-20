package com.fixup.notifications.api;

import java.util.UUID;

/**
 * Extension point for real push delivery (FCM or otherwise). Callers must always treat a failure
 * here as best-effort and never let it block the operation that triggered the notification: see
 * NoopPushNotificationGateway for the placeholder wired today, and messaging.application.
 * SendChatMessage for how a caller is expected to isolate a failure from the rest of its work.
 */
public interface PushNotificationGateway {

    /** @throws PushNotificationException if delivery could not even be attempted or was refused. */
    void send(PushNotification notification);

    record PushNotification(UUID recipientUserId, String title, String body) {
    }
}
