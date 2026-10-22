package com.fixup.properties.api;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-07: los módulos que conocen el trabajo hecho en un inmueble lo registran aquí. El sello
 * Fix&Rent se calcula a partir de este historial, nunca se asigna a mano.
 */
public interface PropertyWorkHistory {

    /** Idempotente por trabajo: registrar dos veces el mismo trabajo no duplica la fila. */
    void recordCompletedWork(CompletedWork work);

    /** Copia la calificación del cliente al trabajo; si el trabajo no está registrado no hace nada. */
    void recordRating(UUID jobId, int score);

    record CompletedWork(UUID propertyId, UUID jobId, UUID requestId, String specialty, String title,
            UUID fixerUserId, Instant completedAt) {
    }
}
