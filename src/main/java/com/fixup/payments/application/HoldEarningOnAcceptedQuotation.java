package com.fixup.payments.application;

import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.OwnerCharge;
import com.fixup.payments.domain.OwnerCharges;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.requests.api.RepairRequestDirectory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-20: aceptar la cotización compromete el dinero, no lo entrega. El ingreso nace retenido
 * y la comisión se congela con la tarifa vigente en ese momento. FR-UC-05: en la misma transacción
 * nace el cobro al propietario, pendiente de pago.
 *
 * El listener participa en la transacción de la aceptación: no puede existir una cotización
 * aceptada sin su ingreso retenido.
 */
@Component
class HoldEarningOnAcceptedQuotation {
    private final FixerEarnings earnings;
    private final OwnerCharges charges;
    private final RepairRequestDirectory requests;

    HoldEarningOnAcceptedQuotation(FixerEarnings earnings, OwnerCharges charges, RepairRequestDirectory requests) {
        this.earnings = earnings;
        this.charges = charges;
        this.requests = requests;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(QuotationAccepted event) {
        var now = Instant.now();
        if (!earnings.existsByQuotation(event.quotationId())) {
            earnings.create(FixerEarning.held(UUID.randomUUID(), event.quotationId(),
                    event.fixerUserId(), event.amount(), now));
        }
        // FR-UC-05: the owner's charge is born with the acceptance, pending until it is paid.
        if (!charges.existsByQuotation(event.quotationId())) {
            var request = requests.require(event.requestId());
            charges.create(OwnerCharge.pending(event.quotationId(), event.requestId(), request.propertyId(),
                    event.ownerUserId(), request.managerUserId(), event.fixerUserId(),
                    request.specialty() == null ? null : request.specialty().name(),
                    request.title(), event.amount(), now));
        }
    }
}
