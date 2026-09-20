package com.fixup.jobs.api;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: el técnico terminó el trabajo. Es el hecho que libera del escrow lo que el
 * propietario ya había comprometido al aceptar la cotización.
 */
public record JobCompleted(UUID jobId, UUID quotationId, UUID fixerUserId, Instant completedAt) {
}
