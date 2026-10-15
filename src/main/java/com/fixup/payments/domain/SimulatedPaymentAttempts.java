package com.fixup.payments.domain;

import java.util.List;
import java.util.UUID;

public interface SimulatedPaymentAttempts {
    List<SimulatedPaymentAttempt> findByPayment(UUID paymentId);
    void create(SimulatedPaymentAttempt attempt);
}
