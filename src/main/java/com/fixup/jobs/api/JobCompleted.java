package com.fixup.jobs.api;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: el técnico terminó el trabajo. Es el hecho que libera del escrow lo que el
 * propietario ya había comprometido al aceptar la cotización.
 */
public record JobCompleted(UUID jobId, UUID quotationId, UUID requestId, UUID ownerUserId,
        UUID fixerUserId, String paymentMethod, long agreedAmount, Long receivedAmount,
        Instant completedAt) {
}
