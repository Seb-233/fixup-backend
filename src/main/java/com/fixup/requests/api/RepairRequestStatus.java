package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-08: ciclo de vida de la solicitud. OPEN y ASSIGNED venían de FR-UC-18; los demás llegan
 * con el reloj de SLA, que necesita saber si el trabajo sigue vivo para exigirle un plazo.
 */
@Schema(enumAsRef = true)
public enum RepairRequestStatus {
    OPEN, ASSIGNED, IN_PROGRESS, ON_HOLD, COMPLETED, CANCELLED
}
