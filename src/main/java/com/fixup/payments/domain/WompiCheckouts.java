package com.fixup.payments.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WompiCheckouts {
    void create(WompiCheckout checkout);

    Optional<WompiCheckout> findByReference(String reference);

    /** Pago de FixUp detrás de cada referencia de checkout conocida (las ajenas no aparecen). */
    Map<String, CheckoutPayment> paymentsByReference(Collection<String> references);

    /** Estado con que FixUp registró cada transacción de la pasarela (las no registradas no aparecen). */
    Map<String, PaymentStatus> recordedTransactions(Collection<String> transactionIds);

    record CheckoutPayment(String checkoutReference, long checkoutAmountInCents, UUID paymentId,
            String paymentReference, String purpose, PaymentStatus status) {
    }
}
