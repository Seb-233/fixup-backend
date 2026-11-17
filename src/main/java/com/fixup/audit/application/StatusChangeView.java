package com.fixup.audit.application;

import com.fixup.audit.api.AuditedResourceType;
import java.time.Instant;
import java.util.UUID;

public record StatusChangeView(
        UUID id,
        AuditedResourceType resourceType,
        UUID resourceId,
        String previousStatus,
        String newStatus,
        UUID changedByUserId,
        String changedBySubject,
        String changedByDisplayName,
        Instant changedAt) {
}
