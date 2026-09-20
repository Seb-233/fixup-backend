package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentConflictException;

/**
 * FR-UC-20: la comisión de la plataforma es del 10%.
 *
 * Se guarda en puntos básicos y se calcula con aritmética entera sobre pesos enteros, así que no
 * hay coma flotante que arrastre un centavo de diferencia entre lo que ve el técnico y lo que
 * queda en la base. La división trunca hacia abajo, de modo que un residuo indivisible queda del
 * lado del técnico y nunca de la plataforma.
 *
 * La tasa se copia en cada ingreso al momento de crearlo: cambiarla mañana no puede reescribir lo
 * que ya se liquidó.
 */
public final class CommissionPolicy {
    public static final int RATE_BASIS_POINTS = 1_000;
    private static final int BASIS_POINTS_DENOMINATOR = 10_000;

    private CommissionPolicy() {
    }

    public static long commissionFor(long grossAmount) {
        requirePositive(grossAmount);
        return grossAmount * RATE_BASIS_POINTS / BASIS_POINTS_DENOMINATOR;
    }

    public static long netFor(long grossAmount) {
        return grossAmount - commissionFor(grossAmount);
    }

    private static void requirePositive(long grossAmount) {
        if (grossAmount <= 0) {
            throw new PaymentConflictException("INVALID_AMOUNT",
                    "The gross amount of an earning must be positive");
        }
    }
}
