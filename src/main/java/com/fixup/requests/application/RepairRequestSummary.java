package com.fixup.requests.application;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaState;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepairRequestSummary(UUID id, UUID propertyId, UUID ownerUserId, Specialty specialty, String title,
        String description, List<UUID> mediaIds, RepairRequestStatus status,
        UUID assignedFixerUserId, Instant createdAt,
        UrgencyLevel urgencyLevel, Instant slaDeadline, SlaState slaState) {

    private static final Duration WARNING_THRESHOLD = Duration.ofMinutes((long) (48 * 60 * 0.20));

    static RepairRequestSummary of(RepairRequest request) {
        SlaState slaState = computeSlaState(request);
        return new RepairRequestSummary(request.id(), request.propertyId(), request.ownerUserId(), request.specialty(),
                request.title(), request.description(), request.mediaIds(), request.status(),
                request.assignedFixerUserId(), request.createdAt(),
                request.urgencyLevel(), request.slaDeadline(), slaState);
    }

    private static SlaState computeSlaState(RepairRequest request) {
        if (request.slaDeadline() == null) {
            return SlaState.NONE;
        }
        if (request.status() == RepairRequestStatus.SLA_BREACHED) {
            return SlaState.BREACHED;
        }
        if (request.status() == RepairRequestStatus.SLA_WARNING) {
            return SlaState.WARNING;
        }
        Instant now = Instant.now();
        if (request.slaDeadline().isBefore(now)) {
            return SlaState.BREACHED;
        }
        Duration remaining = Duration.between(now, request.slaDeadline());
        if (remaining.compareTo(WARNING_THRESHOLD) <= 0) {
            return SlaState.WARNING;
        }
        return SlaState.ON_TRACK;
    }
}
