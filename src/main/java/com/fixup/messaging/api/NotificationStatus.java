package com.fixup.messaging.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether the best-effort push notification for a message succeeded. Never affects whether the
 * message itself was accepted: the attempt always happens before the message is persisted, in the
 * same transaction, so a message row is only ever written once its final outcome is known.
 */
@Schema(enumAsRef = true)
public enum NotificationStatus {
    SENT, FAILED
}
