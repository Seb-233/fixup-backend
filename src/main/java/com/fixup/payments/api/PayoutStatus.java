package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-20: estados de una solicitud de transferencia del saldo del técnico.
 *
 * <ul>
 *   <li>{@code REQUESTED} &mdash; la solicitud fue registrada y los ingresos asociados pasaron
 *       a {@code PAID_OUT}. <strong>En el alcance académico actual no existe integración
 *       bancaria real</strong>: el sistema registra la intención de transferir pero no llama
 *       a ningún proveedor de pagos externo. La liquidación efectiva hacia una cuenta bancaria
 *       es trabajo posterior, fuera del alcance del prototipo.</li>
 * </ul>
 */
@Schema(enumAsRef = true)
public enum PayoutStatus {
    REQUESTED
}
