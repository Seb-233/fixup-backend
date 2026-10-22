package com.fixup.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashSettlements {
    Optional<CashSettlement> findByJob(UUID jobId);
    void create(CashSettlement settlement);
    /** Settlements between the instants; {@code ownerUserId} null means every owner. */
    List<CashSettlement> findSettledBetween(UUID ownerUserId, Instant from, Instant to);
}
