package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-20: estados de una solicitud de transferencia del saldo del técnico.
 *
 * REQUESTED remains valid for historical rows; new simulated payouts finish TRANSFERRED.
 */
@Schema(enumAsRef = true)
public enum PayoutStatus {
    REQUESTED, TRANSFERRED
}
