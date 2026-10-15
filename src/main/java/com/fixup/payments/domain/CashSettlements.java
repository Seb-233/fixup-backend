package com.fixup.payments.domain;

import java.util.Optional;
import java.util.UUID;

public interface CashSettlements {
    Optional<CashSettlement> findByJob(UUID jobId);
    void create(CashSettlement settlement);
}
