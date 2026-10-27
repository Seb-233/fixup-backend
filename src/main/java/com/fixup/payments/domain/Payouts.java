package com.fixup.payments.domain;

import java.util.List;
import java.util.UUID;

public interface Payouts {
    List<Payout> findByFixer(UUID fixerUserId);

    void create(Payout payout);
    void lockFixer(UUID fixerUserId);
    java.util.Optional<Payout> findByOperation(UUID fixerUserId, UUID key);
    void include(Payout payout, FixerEarning earning);
    List<PayoutEntry> entries(UUID payoutId);
}
