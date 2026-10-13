package com.fixup.requests.api;

import com.fixup.fixers.api.Specialty;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable view another module may read to decide its own rules. It carries no photo, no
 * description and no JPA entity: quotations needs to know who decides on the request and whether
 * it still admits offers, messaging who takes part in it, and visits, payments and ratings the
 * property it belongs to.
 */
public record RepairRequestSnapshot(UUID requestId, UUID ownerUserId, Specialty specialty,
        RepairRequestStatus status, UUID assignedFixerUserId, UUID propertyId, UUID requestedByUserId,
        UUID managerUserId, String title, String propertyName, String propertyAddress, String propertyCity) {

    public RepairRequestSnapshot(UUID requestId, UUID ownerUserId, Specialty specialty,
            RepairRequestStatus status, UUID assignedFixerUserId) {
        this(requestId, ownerUserId, specialty, status, assignedFixerUserId, null, ownerUserId, null, null, null,
                null, null);
    }

    public boolean isOpen() {
        return status == RepairRequestStatus.OPEN;
    }

    /**
     * FR-UC-24: the chat is open while a fixer is assigned and the work is not closed. The SLA alert
     * statuses keep the fixer assigned, so they keep the conversation open too.
     */
    public boolean isAssigned() {
        return assignedFixerUserId != null && status != RepairRequestStatus.OPEN
                && status != RepairRequestStatus.COMPLETED && status != RepairRequestStatus.CANCELLED;
    }

    /** FR-UC-25: the owner check travels with the record, not with the screen that displays it. */
    public void requireOwnedBy(UUID userId) {
        if (!ownerUserId.equals(userId)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    /** FR-UC-11: the owner or the real estate manager of the property decides on offers. */
    public boolean isDecidedBy(UUID userId) {
        return ownerUserId.equals(userId) || (managerUserId != null && managerUserId.equals(userId));
    }

    public void requireDecidedBy(UUID userId) {
        if (!isDecidedBy(userId)) {
            throw new RepairRequestAccessDeniedException();
        }
    }

    /**
     * Everyone who takes part in the work: owner, whoever reported it (a tenant, FR-UC-08), the
     * manager of the property and the assigned fixer. A platform admin is never a participant.
     */
    public Set<UUID> participants() {
        var people = new LinkedHashSet<UUID>();
        people.add(ownerUserId);
        if (requestedByUserId != null) {
            people.add(requestedByUserId);
        }
        if (managerUserId != null) {
            people.add(managerUserId);
        }
        if (assignedFixerUserId != null) {
            people.add(assignedFixerUserId);
        }
        return Set.copyOf(people);
    }

    public boolean isParticipant(UUID userId) {
        return userId != null && participants().contains(userId);
    }

    @Override
    public UUID ownerUserId() {
        return ownerUserId;
    }
}
