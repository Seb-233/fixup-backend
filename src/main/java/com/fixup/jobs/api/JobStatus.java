package com.fixup.jobs.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-20 solo necesita saber cuándo el trabajo terminó, porque ese es el momento en que el
 * dinero retenido se libera. Los estados intermedios del técnico —en camino, en ejecución—
 * pertenecen a FR-UC-19 y se agregarán con ese caso.
 */
@Schema(enumAsRef = true)
public enum JobStatus {
    ASSIGNED, COMPLETED
}
