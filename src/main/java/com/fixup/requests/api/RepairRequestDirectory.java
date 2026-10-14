package com.fixup.requests.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * FR-UC-18: synchronous contract consumed by quotations. Modules never reach into another
 * module's repositories, so reading and assigning a request both go through this interface.
 */
public interface RepairRequestDirectory {

    /** @throws RepairRequestNotFoundException when no request carries that identifier. */
    RepairRequestSnapshot require(UUID requestId);

    /** Batch view for project composition; missing identifiers are omitted from the result. */
    List<ProjectRepairRequestSnapshot> findAll(Collection<UUID> requestIds);

    /**
     * Locks the repair request row with PESSIMISTIC_WRITE for a decision.
     *
     * @throws RepairRequestNotFoundException when no request carries that identifier.
     */
    RepairRequestSnapshot lockForDecision(UUID requestId);

    /**
     * Closes the request against the fixer whose quotation was accepted. The caller already runs
     * inside a transaction, so the assignment either commits with the acceptance or not at all.
     *
     * @throws RepairRequestConflictException when the request no longer admits an assignment.
     */
    void assign(UUID requestId, UUID fixerUserId);

    /**
     * FR-UC-19: closes the request when its job is completed, inside the caller's transaction.
     *
     * @throws RepairRequestConflictException when the request has no active assignment.
     */
    void markCompleted(UUID requestId);

    /** FR-UC-11: technicians a manager already invited to quote the request. */
    Set<UUID> invitedFixers(UUID requestId);
}
