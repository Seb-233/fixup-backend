package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebt;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "owner_debts")
class OwnerDebtEntity {
    @Id private UUID id;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private UUID ownerUserId;
    @Column(name = "fixer_user_id", nullable = false, updatable = false) private UUID fixerUserId;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    @Column(name = "quotation_id", nullable = false, updatable = false) private UUID quotationId;
    @Column(nullable = false, updatable = false) private long amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DebtStatus status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "settled_at") private Instant settledAt;
    protected OwnerDebtEntity() { }
    static OwnerDebtEntity from(OwnerDebt debt) { var entity = new OwnerDebtEntity(); entity.id = debt.id(); entity.ownerUserId = debt.ownerUserId(); entity.fixerUserId = debt.fixerUserId(); entity.requestId = debt.requestId(); entity.quotationId = debt.quotationId(); entity.amount = debt.amount(); entity.createdAt = debt.createdAt(); entity.apply(debt); return entity; }
    void apply(OwnerDebt debt) { status = debt.status(); settledAt = debt.settledAt(); }
    OwnerDebt toDomain() { return new OwnerDebt(id, ownerUserId, fixerUserId, requestId, quotationId, amount, status, createdAt, settledAt); }
}
