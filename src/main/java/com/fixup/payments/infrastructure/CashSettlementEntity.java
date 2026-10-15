package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.CashSettlement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cash_settlements")
class CashSettlementEntity {
    @Id private UUID id;
    @Column(name = "job_id", nullable = false, updatable = false) private UUID jobId;
    @Column(name = "quotation_id", nullable = false, updatable = false) private UUID quotationId;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private UUID ownerUserId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false) private UUID fixerUserId;
    @Column(name = "agreed_amount", nullable = false, updatable = false) private long agreedAmount;
    @Column(name = "received_amount", nullable = false, updatable = false) private long receivedAmount;
    @Column(name = "outstanding_amount", nullable = false, updatable = false) private long outstandingAmount;
    @Column(name = "settled_at", nullable = false, updatable = false) private Instant settledAt;

    protected CashSettlementEntity() { }

    static CashSettlementEntity from(CashSettlement value) {
        var entity = new CashSettlementEntity();
        entity.id = value.id();
        entity.jobId = value.jobId();
        entity.quotationId = value.quotationId();
        entity.requestId = value.requestId();
        entity.ownerUserId = value.ownerUserId();
        entity.fixerUserId = value.fixerUserId();
        entity.agreedAmount = value.agreedAmount();
        entity.receivedAmount = value.receivedAmount();
        entity.outstandingAmount = value.outstandingAmount();
        entity.settledAt = value.settledAt();
        return entity;
    }

    CashSettlement toDomain() {
        return new CashSettlement(id, jobId, quotationId, requestId, ownerUserId, fixerUserId,
                agreedAmount, receivedAmount, outstandingAmount, settledAt);
    }
}
