package com.fixup.jobs.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-20 solo necesita saber cuándo el trabajo terminó, porque ese es el momento en que el
 * dinero retenido se libera. Los estados intermedios del técnico —en camino, en ejecución—
 * pertenecen a FR-UC-19 y se agregarán con ese caso.
 * FR-UC-08 agrega IN_PROGRESS y SLA_AT_RISK para monitoreo desde el panel de administración.
 */
@Schema(enumAsRef = true)
public enum JobStatus {
    ASSIGNED, IN_PROGRESS, SLA_AT_RISK, COMPLETED
}
