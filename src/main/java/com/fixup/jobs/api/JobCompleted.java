package com.fixup.jobs.api;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: el técnico terminó el trabajo. Es el hecho que libera el ingreso retenido de un pago
 * por transferencia o liquida el efectivo recibido (FR-UC-05), y el que habilita calificarlo.
 */
public record JobCompleted(UUID jobId, UUID quotationId, UUID requestId, UUID ownerUserId,
        UUID fixerUserId, String paymentMethod, long agreedAmount, Long receivedAmount,
        Instant completedAt) {
}
