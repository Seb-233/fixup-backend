package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.SimulatedPaymentAttempt;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "simulated_payment_attempts")
class SimulatedPaymentAttemptEntity {
    @Id private UUID id;
    @Column(name = "payment_id", nullable = false, updatable = false) private UUID paymentId;
    @Enumerated(EnumType.STRING) @Column(name = "requested_outcome", nullable = false, updatable = false) private PaymentStatus requestedOutcome;
    @Enumerated(EnumType.STRING) @Column(name = "resulting_status", nullable = false, updatable = false) private PaymentStatus resultingStatus;
    @Column(name = "attempted_by_user_id", nullable = false, updatable = false) private UUID attemptedByUserId;
    @Column(name = "attempted_at", nullable = false, updatable = false) private Instant attemptedAt;
    @Column(name = "operation_key", updatable = false) private UUID operationKey;
    @Column(name = "payment_reference", updatable = false, length = 36) private String reference;
    @Column(updatable = false) private Long amount;
    @Enumerated(EnumType.STRING) @Column(updatable = false, length = 24) private com.fixup.payments.api.PaymentMethod instrument;
    @Column(updatable = false, length = 32) private String scenario;
    @Column(name = "gateway_transaction_id", updatable = false, length = 64) private String gatewayTransactionId;
    @Column(name = "failure_reason", updatable = false, length = 300) private String failureReason;
    protected SimulatedPaymentAttemptEntity() { }
    static SimulatedPaymentAttemptEntity from(SimulatedPaymentAttempt a) {
        var e = new SimulatedPaymentAttemptEntity(); e.id=a.id(); e.paymentId=a.paymentId();
        e.requestedOutcome=a.requestedOutcome(); e.resultingStatus=a.resultingStatus();
        e.attemptedByUserId=a.attemptedByUserId(); e.attemptedAt=a.attemptedAt();
        e.operationKey=a.operationKey(); e.reference=a.reference(); e.amount=a.amount(); e.instrument=a.instrument();
        e.scenario=a.scenario(); e.gatewayTransactionId=a.gatewayTransactionId(); e.failureReason=a.failureReason();
        return e;
    }
    SimulatedPaymentAttempt toDomain() {
        return new SimulatedPaymentAttempt(id, paymentId, requestedOutcome, resultingStatus, attemptedByUserId,
                attemptedAt, operationKey, reference, amount, instrument, scenario, gatewayTransactionId, failureReason);
    }
}
