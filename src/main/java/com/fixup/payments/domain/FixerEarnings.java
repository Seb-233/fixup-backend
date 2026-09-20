package com.fixup.payments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FixerEarnings {
    Optional<FixerEarning> findByQuotationForUpdate(UUID quotationId);

    boolean existsByQuotation(UUID quotationId);

    List<FixerEarning> findByFixer(UUID fixerUserId);

    /**
     * Reads the available earnings and locks them: the balance is computed and consumed inside a
     * single transaction, so two concurrent payouts cannot transfer the same money twice.
     */
    List<FixerEarning> findAvailableByFixerForUpdate(UUID fixerUserId);

    void create(FixerEarning earning);

    void update(FixerEarning earning);
}
