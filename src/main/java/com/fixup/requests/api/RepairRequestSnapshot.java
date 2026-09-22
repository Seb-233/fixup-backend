package com.fixup.requests.api;

import com.fixup.fixers.api.Specialty;
import java.util.UUID;

/**
 * Immutable view another module may read to decide its own rules. It carries no photo, no
 * description and no JPA entity: quotations only needs to know who owns the request and whether
 * it still admits offers, messaging additionally needs to know who the assigned fixer is once
 * it does, y notifications necesita la urgencia para redactar el aviso.
 */
public record RepairRequestSnapshot(UUID requestId, UUID ownerUserId, Specialty specialty,
        RepairRequestStatus status, UUID assignedFixerUserId, RepairRequestUrgency urgency) {

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    public boolean isAssigned() {
        return status == RepairRequestStatus.ASSIGNED;
    }

    /** FR-UC-25: the owner check travels with the record, not with the screen that displays it. */
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
