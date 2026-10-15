package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repair_payments")
class RepairPaymentEntity {
    @Id private UUID id;
    @Column(name = "quotation_id", nullable = false, updatable = false) private UUID quotationId;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private UUID ownerUserId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false) private UUID fixerUserId;
    @Column(nullable = false, updatable = false) private long amount;
    @Enumerated(EnumType.STRING) @Column(name = "payment_method", nullable = false, updatable = false) private RepairPaymentMethod paymentMethod;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private PaymentPurpose purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PaymentStatus status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;

    protected RepairPaymentEntity() { }

    static RepairPaymentEntity from(RepairPayment payment) {
        var entity = new RepairPaymentEntity();
        entity.id = payment.id(); entity.quotationId = payment.quotationId(); entity.requestId = payment.requestId();
        entity.ownerUserId = payment.ownerUserId(); entity.fixerUserId = payment.fixerUserId();
        entity.amount = payment.amount(); entity.paymentMethod = payment.paymentMethod(); entity.purpose = payment.purpose();
        entity.createdAt = payment.createdAt(); entity.apply(payment);
        return entity;
    }

    void apply(RepairPayment payment) { status = payment.status(); updatedAt = payment.updatedAt(); completedAt = payment.completedAt(); }

    RepairPayment toDomain() { return new RepairPayment(id, quotationId, requestId, ownerUserId, fixerUserId,
            amount, paymentMethod, purpose, status, createdAt, updatedAt, completedAt); }
}
