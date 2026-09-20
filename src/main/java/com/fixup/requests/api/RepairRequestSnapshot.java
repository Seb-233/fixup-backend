package com.fixup.requests.api;

import com.fixup.fixers.api.Specialty;
import java.util.UUID;

public record RepairRequestSnapshot(UUID requestId, UUID ownerUserId, Specialty specialty,
        RepairRequestStatus status, RepairRequestUrgency urgency) {

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    public void requireOwnedBy(UUID userId) {
        if (!ownerUserId.equals(userId)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    @Override
    public UUID ownerUserId() {
        return ownerUserId;
    }
}
