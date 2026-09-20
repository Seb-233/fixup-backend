package com.fixup.requests.application;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepairRequestSummary(UUID id, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
        RepairRequestUrgency urgency, Instant slaDeadline,
        UUID assignedFixerUserId, Instant createdAt) {

    static RepairRequestSummary of(RepairRequest request) {
        return new RepairRequestSummary(request.id(), request.ownerUserId(), request.specialty(),
                request.title(), request.description(), request.mediaIds(), request.status(),
                request.urgency(), request.slaDeadline(),
                request.assignedFixerUserId(), request.createdAt());
    }
}
