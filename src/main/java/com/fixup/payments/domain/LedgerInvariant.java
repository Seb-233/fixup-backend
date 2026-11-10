package com.fixup.payments.domain;

/**
 * Reglas que cruzan tablas y que ninguna restricción de una sola fila puede expresar.
 *
 * <p>Las CHECK, UNIQUE y políticas RLS ya impiden escribir una fila mala. Estas reglas vigilan
 * lo que queda entre filas: que cada peso cobrado tenga su ingreso, que cada transferencia sume lo
 * que dice y que cada deuda pagada tenga el pago que la cerró. Si alguna aparece, algo escribió por
 * fuera del flujo normal (una migración, un script o un error nuevo) y hay que mirarlo antes de
 * mover más plata.
 */
public enum LedgerInvariant {
    SERVICE_PAYMENT_WITHOUT_EARNING(
            "Pago de servicio aprobado sin un ingreso del técnico por el mismo monto"),
    EARNING_WITHOUT_APPROVED_PAYMENT(
            "Ingreso del técnico sin un pago aprobado que lo respalde"),
    EARNING_COMMISSION_MISMATCH(
            "La comisión guardada no corresponde a la tarifa copiada en el ingreso"),
    PAYOUT_TOTAL_MISMATCH(
            "La transferencia no suma lo mismo que sus ingresos"),
    PAYOUT_ENTRY_MISMATCH(
            "Un ingreso incluido en una transferencia no está marcado como transferido o cambió de neto"),
    PAID_DEBT_WITHOUT_PAYMENT(
            "Deuda marcada como pagada sin un pago aprobado por el mismo monto"),
    DEBT_PAYMENT_NOT_APPLIED(
            "Pago de saldo aprobado cuya deuda sigue pendiente"),
    CASH_SETTLEMENT_DEBT_MISMATCH(
            "El saldo pendiente de una liquidación en efectivo no coincide con la deuda registrada"),
    APPROVED_PAYMENT_WITHOUT_APPROVED_ATTEMPT(
            "Pago aprobado sin un intento aprobado en la auditoría");

    private final String description;

    LedgerInvariant(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
