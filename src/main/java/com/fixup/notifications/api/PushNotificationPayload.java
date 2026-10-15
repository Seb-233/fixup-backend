package com.fixup.notifications.api;

import java.util.Map;
import java.util.UUID;

public record PushNotificationPayload(
        String navigateTo,
        UUID entityId,
        String entityType,
        Map<String, String> data) {

    public PushNotificationPayload(String navigateTo, UUID entityId, String entityType) {
        this(navigateTo, entityId, entityType, null);
    }
}
