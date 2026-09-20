package com.fixup.payments.application;

import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.quotations.api.QuotationAccepted;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-20: aceptar la cotización compromete el dinero, no lo entrega. El ingreso nace retenido
 * y la comisión se congela con la tarifa vigente en ese momento.
 *
 * El listener participa en la transacción de la aceptación: no puede existir una cotización
 * aceptada sin su ingreso retenido.
 */
@Component
class HoldEarningOnAcceptedQuotation {
    private final FixerEarnings earnings;

    HoldEarningOnAcceptedQuotation(FixerEarnings earnings) {
        this.earnings = earnings;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(QuotationAccepted event) {
        if (earnings.existsByQuotation(event.quotationId())) {
            return;
        }
        earnings.create(FixerEarning.held(UUID.randomUUID(), event.quotationId(),
                event.fixerUserId(), event.amount(), Instant.now()));
    }
}
