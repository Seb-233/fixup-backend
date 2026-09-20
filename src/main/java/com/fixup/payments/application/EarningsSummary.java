package com.fixup.payments.application;

import java.util.List;

/**
 * FR-UC-20: el panel de pagos del técnico.
 *
 * availableBalance es lo que puede transferir hoy; heldBalance es lo comprometido por trabajos
 * que todavía no ha cerrado; totalCommission es lo que la plataforma se ha quedado, a la vista y
 * no escondido en la diferencia entre dos cifras.
 */
public record EarningsSummary(long availableBalance, long heldBalance, long paidOutTotal,
        long totalCommission, long grossTotal, List<EarningLine> history) {
}
