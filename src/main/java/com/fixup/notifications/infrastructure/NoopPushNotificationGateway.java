package com.fixup.notifications.infrastructure;

import com.fixup.notifications.api.NotificationStatus;
import com.fixup.notifications.api.PushNotificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder wired until a real FCM integration exists. Returns {@link NotificationStatus#SKIPPED}
 * explicitly -- callers can distinguish "provider not configured" from a real delivery failure
 * ({@code FAILED}) and from confirmed delivery ({@code SENT}).
 *
 * <p><strong>KNOWN FUNCTIONAL DEBT</strong>: Firebase Cloud Messaging (or equivalent) is not yet
 * integrated. Messages are stored correctly and participants can poll via GET /messages, but
 * recipients do not receive a device push. Replacing this class with a real provider is a one-file
 * change with no impact on callers, because the interface contract is already stable.
 */
@Component
class NoopPushNotificationGateway implements PushNotificationGateway {
    private static final Logger LOG = LoggerFactory.getLogger(NoopPushNotificationGateway.class);

    @Override
    public NotificationStatus send(PushNotification notification) {
        LOG.debug("Push notification skipped (no provider configured yet): recipient={}",
                notification.recipientUserId());
        return NotificationStatus.SKIPPED;
    }
}