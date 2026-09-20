package com.fixup.payments.infrastructure;

import com.fixup.payments.api.PayoutStatus;
import com.fixup.payments.domain.Payout;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payouts")
class PayoutEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;
    @Column(name = "earning_count", nullable = false, updatable = false)
    private int earningCount;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PayoutStatus status;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    protected PayoutEntity() {
    }

    static PayoutEntity from(Payout payout) {
        var entity = new PayoutEntity();
        entity.id = payout.id();
        entity.fixerUserId = payout.fixerUserId();
        entity.amount = payout.amount();
        entity.earningCount = payout.earningCount();
        entity.status = payout.status();
        entity.requestedAt = payout.requestedAt();
        return entity;
    }

    Payout toDomain() {
        return new Payout(id, fixerUserId, amount, earningCount, status, requestedAt);
    }
}
