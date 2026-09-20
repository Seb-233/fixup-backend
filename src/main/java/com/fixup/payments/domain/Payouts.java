package com.fixup.payments.domain;

import java.util.List;
import java.util.UUID;

public interface Payouts {
    List<Payout> findByFixer(UUID fixerUserId);

    void create(Payout payout);
}
