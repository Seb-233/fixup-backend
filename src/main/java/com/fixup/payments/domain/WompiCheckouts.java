package com.fixup.payments.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WompiCheckouts {
    void create(WompiCheckout checkout);

    Optional<WompiCheckout> findByReference(String reference);

    /**
     * Quién abrió el checkout de una referencia, para el webhook, que todavía no tiene actor. Es una
     * resolución acotada: no pasa por las políticas de lectura de usuarios (que sin actor no dejan ver
     * nada) y no devuelve nada más del checkout. Solo responde a una sesión sin actor.
     */
    Optional<UUID> openerOf(String reference);

    /** Pago de FixUp detrás de cada referencia de checkout conocida (las ajenas no aparecen). */
    Map<String, CheckoutPayment> paymentsByReference(Collection<String> references);

    /** Estado con que FixUp registró cada transacción de la pasarela (las no registradas no aparecen). */
    Map<String, PaymentStatus> recordedTransactions(Collection<String> transactionIds);

    record CheckoutPayment(String checkoutReference, long checkoutAmountInCents, UUID paymentId,
            String paymentReference, String purpose, PaymentStatus status) {
    }
}
