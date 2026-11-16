package com.fixup.requests.application;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.PaymentMethod;
import com.fixup.requests.api.SlaState;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.SlaPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepairRequestSummary(UUID id, UUID propertyId, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
        UUID assignedFixerUserId, PaymentMethod paymentMethod, Instant createdAt,
        UrgencyLevel urgencyLevel, Instant slaDeadline, SlaState slaState,
        UUID requestedByUserId, UUID managerUserId, String propertyName, String propertyAddress,
        String propertyCity, Instant completedAt) {

    public static RepairRequestSummary of(RepairRequest request) {
        SlaState slaState = computeSlaState(request);
        return new RepairRequestSummary(request.id(), request.propertyId(), request.ownerUserId(), request.specialty(),
                request.title(), request.description(), request.mediaIds(), request.status(),
                request.assignedFixerUserId(), request.paymentMethod(), request.createdAt(),
                request.urgencyLevel(), request.slaDeadline(), slaState,
                request.requestedByUserId(), request.managerUserId(), request.propertyName(),
                request.propertyAddress(), request.propertyCity(), request.completedAt());
    }

    private static SlaState computeSlaState(RepairRequest request) {
        return SlaPolicy.stateOf(request.status(), request.slaDeadline(), Instant.now());
    }
}
