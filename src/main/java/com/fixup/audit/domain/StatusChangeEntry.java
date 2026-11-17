package com.fixup.audit.domain;

import com.fixup.audit.api.AuditedResourceType;
import java.time.Instant;
import java.util.UUID;

public record StatusChangeEntry(
        UUID id,
        AuditedResourceType resourceType,
        UUID resourceId,
        String previousStatus,
        String newStatus,
        UUID changedByUserId,
        String changedBySubject,
        Instant changedAt) {
}
