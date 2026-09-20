package com.fixup.quotations.api;

import java.time.Instant;
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
 * <p>Un consumidor que solo escribe en nuestra propia base y cuyo registro debe existir
 * siempre que la cotización esté aceptada —el trabajo en {@code jobs}, el ingreso retenido en
 * {@code payments}— escucha con {@code @EventListener} y
 * {@code @Transactional(propagation = MANDATORY)}, dentro de la misma transacción. No hay nada
 * externo que proteger, y después del commit existiría una ventana en la que una cotización
 * aceptada no tiene trabajo abierto ni dinero comprometido: el rollback conjunto es
 * justamente la garantía que se quiere.
 */
public record QuotationAccepted(UUID quotationId, UUID requestId, UUID fixerUserId, UUID ownerUserId,
        long amount, Instant acceptedAt) {
}
