package com.fixup.contracts.domain;

import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.api.PaymentFrequency;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Contracts {
    Optional<LeaseContract> findById(UUID id);

    Optional<LeaseContract> findByIdForUpdate(UUID id);

    List<LeaseContract> findByUser(UUID userId, String role, ContractStatus status,
            UUID propertyId, LocalDate startFrom, LocalDate endUntil, int limit);

    List<LeaseContract> findByProperty(UUID propertyId, ContractStatus status, int limit);

    void create(LeaseContract contract);

    void update(LeaseContract contract);
}
