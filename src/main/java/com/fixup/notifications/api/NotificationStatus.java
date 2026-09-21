package com.fixup.notifications.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether the best-effort push notification for a message succeeded. Never affects whether the
 * message itself was accepted: the attempt always happens before the message is persisted, in the
 * same transaction, so a message row is only ever written once its final outcome is known.
 *
 * <p>SENT: the provider confirmed delivery was queued.
 * SKIPPED: no provider is configured yet (NoopPushNotificationGateway); message was saved normally.
 * FAILED: a real provider was attempted but reported an error or was unreachable.
 */
@Schema(enumAsRef = true)
public enum NotificationStatus {
    SENT, SKIPPED, FAILED
}