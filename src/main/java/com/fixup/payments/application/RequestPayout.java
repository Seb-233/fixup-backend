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
    public PayoutSummary execute(CurrentActor actor, UUID operationKey) {
        PaymentAccess.requireActiveFixer(actor);
        java.util.Objects.requireNonNull(operationKey, "operationKey");
        payouts.lockFixer(actor.internalUserId());
        var previous = payouts.findByOperation(actor.internalUserId(), operationKey);
        if (previous.isPresent()) {
            var payout = previous.orElseThrow();
            return PayoutSummary.of(payout, payouts.entries(payout.id()));
        }

        var available = earnings.findAvailableByFixerForUpdate(actor.internalUserId());
        final long amount;
        try { amount = available.stream().mapToLong(FixerEarning::netAmount).reduce(0L, Math::addExact); }
        catch (ArithmeticException overflow) { throw new com.fixup.payments.api.PaymentConflictException("PAYOUT_AMOUNT_OVERFLOW", "Balance exceeds supported integer precision"); }

        // Payout.requested rechaza un saldo de cero: no se registran transferencias vacías.
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var payout = Payout.requested(UUID.randomUUID(), actor.internalUserId(), amount,
                available.size(), now, operationKey).transferred(now);
        payouts.create(payout);

        available.forEach(earning -> {
            payouts.include(payout, earning);
            earnings.update(earning.payOut(now));
        });

        return PayoutSummary.of(payout, payouts.entries(payout.id()));
    }
}
