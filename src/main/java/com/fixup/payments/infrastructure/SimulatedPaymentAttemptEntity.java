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
    protected SimulatedPaymentAttemptEntity() { }
    static SimulatedPaymentAttemptEntity from(SimulatedPaymentAttempt attempt) { var entity = new SimulatedPaymentAttemptEntity(); entity.id = attempt.id(); entity.paymentId = attempt.paymentId(); entity.requestedOutcome = attempt.requestedOutcome(); entity.resultingStatus = attempt.resultingStatus(); entity.attemptedByUserId = attempt.attemptedByUserId(); entity.attemptedAt = attempt.attemptedAt(); return entity; }
    SimulatedPaymentAttempt toDomain() { return new SimulatedPaymentAttempt(id, paymentId, requestedOutcome, resultingStatus, attemptedByUserId, attemptedAt); }
}
