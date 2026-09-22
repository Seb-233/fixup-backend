package com.fixup.requests.api;

import java.util.UUID;

/**
 * FR-UC-18: synchronous contract consumed by quotations. Modules never reach into another
 * module's repositories, so reading and assigning a request both go through this interface.
 */
public interface RepairRequestDirectory {

    /** @throws RepairRequestNotFoundException when no request carries that identifier. */
    RepairRequestSnapshot require(UUID requestId);

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
     * FR-UC-08 + FR-UC-20: cierra la solicitud cuando el técnico cierra el trabajo.
     *
     * <p>El cierre tiene una sola puerta, {@code POST /jobs/{jobId}/complete}, que es la que libera
     * el dinero retenido. Si la solicitud tuviera además su propia ruta para terminarse habría dos
     * formas de "terminar": una movería el estado sin liberar el escrow y la otra liberaría el
     * dinero dejando la solicitud asignada para siempre. Por eso el cierre entra por aquí, igual
     * que la asignación entra por {@link #assign}, y confirma en la misma transacción.
     *
     * @throws RepairRequestConflictException when the request is no longer active.
     */
    void complete(UUID requestId);
}
