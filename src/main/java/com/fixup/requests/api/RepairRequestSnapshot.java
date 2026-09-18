package com.fixup.requests.api;

import java.util.UUID;

/**
 * Immutable view another module may read to decide its own rules. It carries no photo, no
 * description and no JPA entity: quotations only needs to know who owns the request and whether
 * it still admits offers.
 */
public record RepairRequestSnapshot(UUID requestId, UUID ownerUserId, Specialty specialty,
        RepairRequestStatus status) {

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    /** FR-UC-25: the owner check travels with the record, not with the screen that displays it. */
    public void requireOwnedBy(UUID userId) {
        if (!ownerUserId.equals(userId)) {
            throw new RepairRequestAccessDeniedException();
        }
    }
}
