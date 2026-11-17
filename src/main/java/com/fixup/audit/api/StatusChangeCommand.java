package com.fixup.audit.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StatusChangeCommand(
        AuditedResourceType resourceType,
        UUID resourceId,
        String previousStatus,
        String newStatus,
        UUID changedByUserId,
        String changedBySubject,
        Instant changedAt) {

    public StatusChangeCommand {
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(changedBySubject, "changedBySubject must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
    }

    public static StatusChangeCommand user(AuditedResourceType resourceType, UUID resourceId,
            String previousStatus, String newStatus, UUID changedByUserId, String changedBySubject,
            Instant changedAt) {
        Objects.requireNonNull(changedByUserId, "changedByUserId must not be null");
        return new StatusChangeCommand(resourceType, resourceId, previousStatus, newStatus,
                changedByUserId, changedBySubject, changedAt);
    }

    public static StatusChangeCommand system(AuditedResourceType resourceType, UUID resourceId,
            String previousStatus, String newStatus, Instant changedAt) {
        return new StatusChangeCommand(resourceType, resourceId, previousStatus, newStatus, null,
                "SYSTEM", changedAt);
    }
}
