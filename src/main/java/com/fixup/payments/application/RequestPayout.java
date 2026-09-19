package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.Payout;
import com.fixup.payments.domain.Payouts;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-20: el técnico solicita transferir su saldo disponible.
 *
 * Este es el caso que obliga a las transacciones ACID del ASR. Leer el saldo, crear la solicitud
 * y marcar los ingresos como transferidos ocurre todo dentro de una transacción, y las filas se
 * bloquean antes de sumar: dos solicitudes simultáneas no pueden llevarse el mismo dinero.
 */
@Service
public class RequestPayout {
    private final FixerEarnings earnings;
    private final Payouts payouts;

    RequestPayout(FixerEarnings earnings, Payouts payouts) {
        this.earnings = earnings;
        this.payouts = payouts;
    }

    @Transactional
    public PayoutSummary execute(CurrentActor actor) {
        PaymentAccess.requireActiveFixer(actor);

        var available = earnings.findAvailableByFixerForUpdate(actor.internalUserId());
        var amount = available.stream().mapToLong(FixerEarning::netAmount).sum();

        // Payout.requested rechaza un saldo de cero: no se registran transferencias vacías.
        var payout = Payout.requested(UUID.randomUUID(), actor.internalUserId(), amount,
                available.size(), Instant.now());
        payouts.create(payout);

        var now = Instant.now();
        available.forEach(earning -> earnings.update(earning.payOut(now)));

        return PayoutSummary.of(payout);
    }
}
