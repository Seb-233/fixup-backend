package com.fixup.quotations.api;

import com.fixup.requests.api.PaymentMethod;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * FR-UC-18: el propietario aceptó una cotización. Es el hecho del que cuelgan los ingresos del
 * técnico (FR-UC-20); el monto viaja bruto y cada módulo aplica sus propias reglas sobre él.
 * 
 * Un consumidor con efecto externo —correo, notificación push, pasarela de pago, cualquier
 * llamada que no se pueda deshacer con un rollback— DEBE escuchar con
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}: si la aceptación se revierte
 * después, el correo ya salió y no hay forma de recogerlo.
 *
 * <p>Los consumidores internos escuchan con {@code @EventListener} y
 * {@code @Transactional(propagation = MANDATORY)} para compartir la transacción. En FR-UC-05
 * solo CASH abre un Job desde este evento; TRANSFER queda bloqueado por su pago pendiente y
 * ningún método crea un earning durante la aceptación.
 */
public record QuotationAccepted(UUID quotationId, UUID requestId, UUID fixerUserId, UUID ownerUserId,
        long amount, Instant acceptedAt, PaymentMethod paymentMethod) {
    public QuotationAccepted {
        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
    }
}
