package com.fixup.payments.application;

import com.fixup.payments.domain.CashSettlement;
import java.time.Instant;
import java.util.UUID;

public record CashSettlementDetail(UUID settlementId, UUID jobId, UUID quotationId,
        UUID requestId, long agreedAmount, long receivedAmount, long outstandingAmount,
        UUID debtId, Instant settledAt) {
    static CashSettlementDetail from(CashSettlement settlement, UUID debtId) {
        return new CashSettlementDetail(settlement.id(), settlement.jobId(),
                settlement.quotationId(), settlement.requestId(), settlement.agreedAmount(),
                settlement.receivedAmount(), settlement.outstandingAmount(), debtId,
                settlement.settledAt());
    }
}
