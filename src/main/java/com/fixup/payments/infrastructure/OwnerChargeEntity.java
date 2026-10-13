package com.fixup.payments.infrastructure;

import com.fixup.payments.api.ChargeStatus;
import com.fixup.payments.api.PaymentMethod;
import com.fixup.payments.domain.OwnerCharge;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "owner_payments")
class OwnerChargeEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "quotation_id", nullable = false, updatable = false)
    private UUID quotationId;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "property_id", updatable = false)
    private UUID propertyId;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;
    @Column(name = "manager_user_id", updatable = false)
    private UUID managerUserId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "specialty", length = 32, updatable = false)
    private String specialty;
    @Column(name = "concept", length = 150, updatable = false)
    private String concept;
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;
    @Column(name = "reference", nullable = false, length = 32, updatable = false)
    private String reference;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChargeStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 24)
    private PaymentMethod method;
    @Column(name = "gateway_transaction_id", length = 64)
    private String gatewayTransactionId;
    @Column(name = "failure_reason", length = 300)
    private String failureReason;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "paid_by_user_id")
    private UUID paidByUserId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "approved_at")
    private Instant approvedAt;

    protected OwnerChargeEntity() {
    }

    static OwnerChargeEntity from(OwnerCharge charge) {
        var entity = new OwnerChargeEntity();
        entity.id = charge.id();
        entity.quotationId = charge.quotationId();
        entity.requestId = charge.requestId();
        entity.propertyId = charge.propertyId();
        entity.ownerUserId = charge.ownerUserId();
        entity.managerUserId = charge.managerUserId();
        entity.fixerUserId = charge.fixerUserId();
        entity.specialty = charge.specialty();
        entity.concept = charge.concept();
        entity.amount = charge.amount();
        entity.reference = charge.reference();
        entity.createdAt = charge.createdAt();
        entity.apply(charge);
        return entity;
    }

    /** The amount and the reference never change: only the outcome of each attempt does. */
    void apply(OwnerCharge charge) {
        status = charge.status();
        method = charge.method();
        gatewayTransactionId = charge.gatewayTransactionId();
        failureReason = charge.failureReason();
        attempts = charge.attempts();
        paidByUserId = charge.paidByUserId();
        updatedAt = charge.updatedAt();
        approvedAt = charge.approvedAt();
    }

    OwnerCharge toDomain() {
        return new OwnerCharge(id, quotationId, requestId, propertyId, ownerUserId, managerUserId, fixerUserId,
                specialty, concept, amount, reference, status, method, gatewayTransactionId, failureReason, attempts,
                paidByUserId, createdAt, updatedAt, approvedAt);
    }
}
