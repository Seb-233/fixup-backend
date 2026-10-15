package com.fixup.remodelings.application;

import com.fixup.remodelings.api.RemodelingProgressStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RemodelingProjectDetail(UUID id, UUID propertyId, UUID ownerUserId, String title,
        String description, RemodelingProgressStatus progressStatus, int requirementCount,
        int assignedCount, int completedCount, List<RemodelingRequirementSummary> requirements,
        Instant createdAt, Instant updatedAt) {
    public RemodelingProjectDetail {
        requirements = List.copyOf(requirements);
    }
}
