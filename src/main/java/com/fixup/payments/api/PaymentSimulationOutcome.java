package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Closed set of business outcomes exposed by the academic payment simulator. */
@Schema(enumAsRef = true)
public enum PaymentSimulationOutcome {
    APPROVED,
    DECLINED,
    PENDING,
    ERROR
}
