package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-20: el ciclo del dinero del técnico.
 *
 * <ul>
 *   <li>{@code HELD} &mdash; el propietario aceptó la cotización y el monto quedó comprometido
 *       en escrow; el trabajo todavía no está terminado, así que el técnico aún no lo ganó.</li>
 *   <li>{@code AVAILABLE} &mdash; el trabajo se cerró y el ingreso quedó liberado; el técnico
 *       puede solicitar su transferencia.</li>
 *   <li>{@code PAID_OUT} &mdash; el saldo fue consumido por una solicitud de transferencia
 *       registrada en el sistema ({@code POST /payments/me/payouts}) y ya no está disponible
 *       para una segunda solicitud. <strong>En el alcance académico actual esto no implica que
 *       exista una transferencia bancaria confirmada por una entidad financiera</strong>: la
 *       integración con un proveedor de pagos externo queda fuera del alcance del prototipo.</li>
 * </ul>
 */
@Schema(enumAsRef = true)
public enum EarningStatus {
    HELD, AVAILABLE, PAID_OUT
}
