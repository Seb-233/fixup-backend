package com.fixup.requests.api;

import com.fixup.fixers.api.Specialty;
import java.util.UUID;

/** Minimal request view used to compose a remodeling without exposing requests internals. */
public record ProjectRepairRequestSnapshot(UUID requestId, Specialty specialty,
        RepairRequestStatus status, UUID assignedFixerUserId) {
}
