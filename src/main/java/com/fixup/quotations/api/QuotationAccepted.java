package com.fixup.quotations.api;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-18: el propietario aceptó una cotización. Es el hecho del que cuelgan los ingresos del
 * técnico (FR-UC-20); el monto viaja bruto y cada módulo aplica sus propias reglas sobre él.
 */
public record QuotationAccepted(UUID quotationId, UUID requestId, UUID fixerUserId, UUID ownerUserId,
        long amount, Instant acceptedAt) {
}
