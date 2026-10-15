package com.fixup.payments.application;

import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebt;
import java.time.Instant;
import java.util.UUID;

public record OwnerDebtDetail(UUID debtId, UUID requestId, UUID quotationId, long amount,
        DebtStatus status, Instant createdAt, Instant settledAt) {
    static OwnerDebtDetail from(OwnerDebt debt) {
        return new OwnerDebtDetail(debt.id(), debt.requestId(), debt.quotationId(), debt.amount(),
                debt.status(), debt.createdAt(), debt.settledAt());
    }
}
