package com.fixup.jobs.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-19: ASSIGNED al aceptar la cotización, SCHEDULED cuando el técnico acuerda la fecha,
 * IN_PROGRESS mientras ejecuta y COMPLETED al cerrarlo, que es el momento en que el dinero
 * retenido se puede liberar (FR-UC-20). SLA_AT_RISK lo usa el panel de administración (FR-UC-08)
 * como lectura derivada y nunca se persiste.
 */
@Schema(enumAsRef = true)
public enum JobStatus {
    ASSIGNED, SCHEDULED, IN_PROGRESS, SLA_AT_RISK, COMPLETED
}
