package com.fixup.notifications.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum NotificationType {
    REQUEST_CREATED_URGENT,
    REQUEST_SLA_WARNING,
    REQUEST_SLA_BREACHED,
    QUOTATION_RECEIVED,
    QUOTATION_ACCEPTED,
    JOB_ASSIGNED,
    PROPERTY_PUBLISHED,
    PROPERTY_BULK_FINISHED,
    CONTRACT_CREATED,
    CONTRACT_EXPIRING_30D,
    CONTRACT_EXPIRING_7D,
    CONTRACT_EXPIRED,
    CHAT_MESSAGE_RECEIVED
}
