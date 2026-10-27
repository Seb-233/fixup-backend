package com.fixup.payments.domain;

import java.util.List;
import java.util.UUID;

public interface SimulatedPaymentAttempts {
    List<SimulatedPaymentAttempt> findByPayment(UUID paymentId);
    java.util.Optional<SimulatedPaymentAttempt> findByOperation(UUID paymentId, UUID operationKey);
    void create(SimulatedPaymentAttempt attempt);
}
