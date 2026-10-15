package com.fixup.contracts.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RentalContracts {
    RentalContract save(RentalContract contract);
    Optional<RentalContract> findById(UUID id);
    List<RentalContract> findByOwnerOrTenant(UUID ownerUserId, UUID tenantUserId);
    List<RentalContract> findExpiringWithinDays(int daysFromNow, LocalDate today);
    List<UUID> findOverlappingActiveContractIds(UUID propertyId, LocalDate start, LocalDate end);
    List<RentalContract> findActiveEndingBetween(LocalDate fromInclusive, LocalDate toInclusive);
}
