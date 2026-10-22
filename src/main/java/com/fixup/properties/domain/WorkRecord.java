package com.fixup.properties.domain;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-07: un trabajo terminado en el inmueble, tal como se muestra en su historial público. */
public record WorkRecord(UUID id, UUID propertyId, UUID jobId, UUID requestId, String specialty, String title,
        UUID fixerUserId, Instant completedAt, Integer rating) {
}
