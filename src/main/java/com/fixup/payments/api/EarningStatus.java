package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-20: el ciclo del dinero del técnico.
 * HELD es el escrow —comprometido por el propietario pero aún no ganado—, AVAILABLE es lo que
 * quedó libre al cerrar el trabajo, y PAID_OUT lo que ya salió en una transferencia.
 */
@Schema(enumAsRef = true)
public enum EarningStatus {
    HELD, AVAILABLE, PAID_OUT
}
