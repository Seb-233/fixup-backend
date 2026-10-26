package com.fixup.payments.domain;

import com.fixup.payments.api.PaymentMethod;
import java.util.UUID;

/**
 * FR-UC-05: el puerto hacia la pasarela de pagos. En esta entrega la única implementación es la
 * pasarela de pruebas (sin dinero ni datos bancarios reales), pero el caso de uso no lo sabe.
 */
public interface PaymentGateway {

    Result charge(Attempt attempt);

    record Attempt(UUID chargeId, String reference, long amount, PaymentMethod method, String scenario) {
    }

    record Result(PaymentStatus status, String transactionId, String failureReason) {
        public Result {
            java.util.Objects.requireNonNull(status);
            java.util.Objects.requireNonNull(transactionId);
        }
    }
}
