package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * El alcance académico llega hasta registrar la solicitud: no hay integración bancaria real,
 * igual que FR-UC-22 declara para los pagos. La liquidación efectiva es trabajo posterior.
 */
@Schema(enumAsRef = true)
public enum PayoutStatus {
    REQUESTED
}
