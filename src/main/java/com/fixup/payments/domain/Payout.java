package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PayoutStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: la solicitud de transferencia del técnico a su cuenta bancaria.
 *
 * <p>En el alcance académico actual el estado siempre es {@code REQUESTED}: el sistema registra
 * la intención de transferir pero no llama a ningún proveedor de pagos externo. Una solicitud
 * {@code REQUESTED} es, por tanto, <em>registrada en el sistema</em>, no
 * <em>ejecutada por una entidad financiera</em>. La liquidación efectiva hacia una cuenta bancaria
 * es trabajo posterior, fuera del alcance del prototipo.
 */
public record Payout(UUID id, UUID fixerUserId, long amount, int earningCount, PayoutStatus status,
        Instant requestedAt) {

    public static Payout requested(UUID id, UUID fixerUserId, long amount, int earningCount,
            Instant now) {
        if (amount <= 0) {
            throw new PaymentConflictException("NO_AVAILABLE_BALANCE",
                    "There is no available balance to transfer");
        }
        return new Payout(id, fixerUserId, amount, earningCount, PayoutStatus.REQUESTED, now);
    }
}
