package com.fixup.quotations.api;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-18: el propietario aceptó una cotización. Es el hecho del que cuelgan los ingresos del
 * técnico (FR-UC-20); el monto viaja bruto y cada módulo aplica sus propias reglas sobre él.
 * 
 * Downstream consumers in jobs, payments, and notifications MUST consume this event using
 * @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) to prevent external
 * side effects before the transaction commits.
 */
public record QuotationAccepted(UUID quotationId, UUID requestId, UUID fixerUserId, UUID ownerUserId,
        long amount, Instant acceptedAt) {
}
