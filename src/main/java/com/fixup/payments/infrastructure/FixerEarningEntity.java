package com.fixup.payments.infrastructure;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.domain.FixerEarning;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fixer_earnings")
class FixerEarningEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "quotation_id", nullable = false, updatable = false)
    private UUID quotationId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false)
    private UUID fixerUserId;
    @Column(name = "gross_amount", nullable = false, updatable = false)
    private long grossAmount;
    @Column(name = "commission_amount", nullable = false, updatable = false)
    private long commissionAmount;
    @Column(name = "net_amount", nullable = false, updatable = false)
    private long netAmount;
    @Column(name = "commission_rate_bps", nullable = false, updatable = false)
    private int commissionRateBasisPoints;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private EarningStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "released_at")
    private Instant releasedAt;
    @Column(name = "paid_out_at")
    private Instant paidOutAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FixerEarningEntity() {
    }

    static FixerEarningEntity from(FixerEarning earning) {
        var entity = new FixerEarningEntity();
        entity.id = earning.id();
        entity.quotationId = earning.quotationId();
        entity.fixerUserId = earning.fixerUserId();
        entity.grossAmount = earning.grossAmount();
        entity.commissionAmount = earning.commissionAmount();
        entity.netAmount = earning.netAmount();
        entity.commissionRateBasisPoints = earning.commissionRateBasisPoints();
        entity.createdAt = earning.createdAt();
        entity.apply(earning);
        return entity;
    }

    /** Money is never re-priced: only where it sits in its lifecycle can change. */
    void apply(FixerEarning earning) {
        status = earning.status();
        releasedAt = earning.releasedAt();
        paidOutAt = earning.paidOutAt();
        updatedAt = earning.updatedAt();
    }

    FixerEarning toDomain() {
        return new FixerEarning(id, quotationId, fixerUserId, grossAmount, commissionAmount,
                netAmount, commissionRateBasisPoints, status, createdAt, releasedAt, paidOutAt,
                updatedAt);
    }
}
