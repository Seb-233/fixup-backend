package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentMethod;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.OwnerCharge;
import com.fixup.payments.domain.OwnerCharges;
import com.fixup.payments.domain.PaymentGateway;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-05 y FR-UC-22: el propietario paga la cotización aceptada en la pasarela de pruebas. Se
 * validan usuario, referencia y valor; la aprobación deja el dinero en garantía y, si el técnico ya
 * cerró el trabajo, lo libera en la misma transacción.
 */
@Service
public class PayCharge {
    private final OwnerCharges charges;
    private final FixerEarnings earnings;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;

    PayCharge(OwnerCharges charges, FixerEarnings earnings, PaymentGateway gateway, ApplicationEventPublisher events) {
        this.charges = charges;
        this.earnings = earnings;
        this.gateway = gateway;
        this.events = events;
    }

    @Transactional
    public OwnerCharge execute(CurrentActor actor, UUID chargeId, String reference, long amount, PaymentMethod method,
            String scenario) {
        PaymentAccess.requireActivePayer(actor);
        if (method == null) {
            throw new PaymentConflictException("METHOD_REQUIRED", "Choose a sandbox payment method");
        }
        // Under RLS another owner's charge is not visible at all; both cases answer as a denied payment.
        var charge = charges.findByIdForUpdate(chargeId).orElseThrow(PaymentAccessDeniedException::new);
        charge.requireAttempt(actor.internalUserId(), reference, amount);

        var result = gateway.charge(new PaymentGateway.Attempt(charge.id(), charge.reference(), charge.amount(), method,
                scenario));
        var now = Instant.now();
        if (!result.approved()) {
            var declined = charge.decline(actor.internalUserId(), method, result.transactionId(), result.failureReason(),
                    now);
            charges.update(declined);
            events.publishEvent(NotificationRequested.of(actor.internalUserId(), NotificationType.PAYMENT_DECLINED,
                    "Pago rechazado", result.failureReason() + ". Puedes intentarlo de nuevo con otro medio de prueba.",
                    "/requests/" + charge.requestId(), charge.id(), "CHARGE"));
            return declined;
        }

        var approved = charge.approve(actor.internalUserId(), method, result.transactionId(), now);
        charges.update(approved);
        earnings.findByQuotationForUpdate(charge.quotationId()).ifPresent(earning -> {
            var funded = earning.fund(now);
            earnings.update(funded.isReleasable() ? funded.release(now) : funded);
        });
        events.publishEvent(NotificationRequested.of(charge.fixerUserId(), NotificationType.PAYMENT_APPROVED,
                "El cliente pagó el trabajo",
                "Recibimos el pago de \"" + charge.concept() + "\". Queda en garantía hasta que cierres el trabajo.",
                "/payments/earnings", charge.id(), "CHARGE"));
        if (!charge.ownerUserId().equals(actor.internalUserId())) {
            events.publishEvent(NotificationRequested.of(charge.ownerUserId(), NotificationType.PAYMENT_APPROVED,
                    "Se pagó una reparación de tu inmueble",
                    "Tu administrador pagó \"" + charge.concept() + "\" con la referencia " + charge.reference() + ".",
                    "/requests/" + charge.requestId(), charge.id(), "CHARGE"));
        }
        return approved;
    }
}
