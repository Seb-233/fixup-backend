package com.fixup.requests.api;

import com.fixup.fixers.api.Specialty;
import java.util.UUID;

/** Minimal request view used to compose a remodeling without exposing requests internals. */
public record ProjectRepairRequestSnapshot(UUID requestId, Specialty specialty,
        RepairRequestStatus status, UUID assignedFixerUserId, PaymentMethod paymentMethod) {

    /** Compatibility constructor for repair requests persisted before FR-UC-05. */
    public ProjectRepairRequestSnapshot(UUID requestId, Specialty specialty,
            RepairRequestStatus status, UUID assignedFixerUserId) {
        this(requestId, specialty, status, assignedFixerUserId, null);
    }
}
