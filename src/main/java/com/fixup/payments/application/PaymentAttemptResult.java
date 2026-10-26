package com.fixup.payments.application;

import com.fixup.payments.api.PaymentMethod;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.SimulatedPaymentAttempt;
import java.time.Instant;
import java.util.UUID;

/** Immutable response to an operation, including on replay after a later attempt. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PaymentAttemptResult(UUID paymentId, UUID attemptId, UUID operationKey, String reference,
        Long amount, PaymentMethod instrument, PaymentStatus status, UUID attemptedByUserId,
        String gatewayTransactionId, String failureReason, Instant attemptedAt) {
    static PaymentAttemptResult of(SimulatedPaymentAttempt a) {
        return new PaymentAttemptResult(a.paymentId(), a.id(), a.operationKey(), a.reference(), a.amount(),
                a.instrument(), a.resultingStatus(), a.attemptedByUserId(), a.gatewayTransactionId(),
                a.failureReason(), a.attemptedAt());
    }
}
