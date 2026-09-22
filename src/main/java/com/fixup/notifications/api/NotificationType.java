package com.fixup.notifications.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-10: taxonomía cerrada de avisos. Solo figuran los que algún escucha emite de verdad; un
 * valor sin emisor es un tipo que nadie puede recibir y que habría que explicar en la sustentación.
 */
@Schema(enumAsRef = true)
public enum NotificationType {
    REPAIR_REQUEST_OPENED,
    REPAIR_REQUEST_ASSIGNED,
    REPAIR_REQUEST_STARTED,
    REPAIR_REQUEST_COMPLETED,
    REPAIR_REQUEST_CANCELLED,
    REPAIR_REQUEST_ON_HOLD,
    REPAIR_REQUEST_RESUMED,
    REPAIR_REQUEST_URGENCY_CHANGED,
    SLA_WARNING,
    SLA_BREACH,
    NEW_QUOTATION,
    QUOTATION_ACCEPTED,
    QUOTATION_REJECTED,
    CONTRACT_CREATED,
    CONTRACT_SIGNED,
    CONTRACT_RENEWED,
    CONTRACT_TERMINATED,
    CONTRACT_CANCELLED,
    PROPERTY_PUBLISHED,
    PROPERTY_BATCH_PUBLISHED,
    PROPERTY_UNLISTED
}
