package com.fixup.remodelings.application;

import com.fixup.remodelings.api.RemodelingProgressStatus;
import java.time.Instant;
import java.util.UUID;

public record RemodelingProjectSummary(UUID id, UUID propertyId, String title,
        RemodelingProgressStatus progressStatus, int requirementCount, int assignedCount,
        int completedCount, Instant createdAt) {
}
