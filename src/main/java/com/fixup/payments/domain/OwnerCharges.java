package com.fixup.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnerCharges {
    boolean existsByQuotation(UUID quotationId);

    void create(OwnerCharge charge);

    void update(OwnerCharge charge);

    Optional<OwnerCharge> findById(UUID id);

    Optional<OwnerCharge> findByIdForUpdate(UUID id);

    Optional<OwnerCharge> findByRequest(UUID requestId);

    /** Cobros del propietario o de los inmuebles que el usuario administra, más recientes primero. */
    List<OwnerCharge> findByPayer(UUID userId);

    List<OwnerCharge> findApprovedBetween(UUID payerOrNull, Instant from, Instant to);
}
