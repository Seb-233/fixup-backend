package com.fixup.notifications.infrastructure;

import com.fixup.notifications.api.PushNotificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder wired until a real FCM integration exists: it never fails and never actually delivers
 * anything. Every caller of PushNotificationGateway already has to treat delivery as best-effort
 * (see the interface), so swapping this for a real client later is a one-file change with no impact
 * on any caller's error handling.
 */
@Component
class NoopPushNotificationGateway implements PushNotificationGateway {
    private static final Logger LOG = LoggerFactory.getLogger(NoopPushNotificationGateway.class);

    @Override
    public void send(PushNotification notification) {
        LOG.debug("Push notification not sent (no provider configured yet): recipient={}",
                notification.recipientUserId());
    }
}
