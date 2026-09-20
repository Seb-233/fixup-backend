package com.fixup.requests.application;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a repair request. The owner identifier travels so the fixer knows whose work it
 * is, but no contact detail of either party is exposed here.
 */
public record RepairRequestSummary(UUID id, UUID propertyId, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
        UUID assignedFixerUserId, Instant createdAt) {

    static RepairRequestSummary of(RepairRequest request) {
        return new RepairRequestSummary(request.id(), request.propertyId(), request.ownerUserId(), request.specialty(),
                request.title(), request.description(), request.mediaIds(), request.status(),
                request.assignedFixerUserId(), request.createdAt());
    }
}
