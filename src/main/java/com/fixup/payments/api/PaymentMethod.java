package com.fixup.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * FR-UC-22: medios de la pasarela de pruebas. Ninguno recibe ni guarda datos de tarjeta, cuenta o
 * celular reales: son nombres de escenarios de la pasarela simulada.
 *
 * <p>INT-01: {@code WOMPI} marca los intentos resueltos por Wompi. El pagador escribe sus datos en el
 * checkout alojado de Wompi; FixUp solo guarda el identificador de la transacción y su resultado.
 */
@Schema(name = "SandboxPaymentInstrument", enumAsRef = true)
public enum PaymentMethod {
    SANDBOX_CARD,
    SANDBOX_PSE,
    SANDBOX_NEQUI,
    WOMPI
}
