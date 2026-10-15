package com.fixup.contracts.infrastructure;

import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaRentalContracts implements RentalContracts {
    private final RentalContractJpaRepository repository;

    JpaRentalContracts(RentalContractJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public RentalContract save(RentalContract contract) {
        return repository.save(new RentalContractEntity(contract)).toDomain();
    }

    @Override
    public Optional<RentalContract> findById(UUID id) {
        return repository.findById(id).map(RentalContractEntity::toDomain);
    }

    @Override
    public List<RentalContract> findByOwnerOrTenant(UUID ownerUserId, UUID tenantUserId) {
        return repository.findByOwnerUserIdOrTenantUserIdOrderByCreatedAtDesc(ownerUserId, tenantUserId)
            .stream()
            .map(RentalContractEntity::toDomain)
            .toList();
    }

    @Override
    public List<RentalContract> findExpiringWithinDays(int daysFromNow, LocalDate today) {
        LocalDate limit = today.plusDays(daysFromNow);
        return repository.findActiveExpiringBetween(today, limit)
            .stream()
            .map(RentalContractEntity::toDomain)
            .toList();
    }

    @Override
    public List<UUID> findOverlappingActiveContractIds(UUID propertyId, LocalDate start, LocalDate end) {
        return repository.findOverlappingActiveContractIds(propertyId, start, end);
    }

    @Override
    public List<RentalContract> findActiveEndingBetween(LocalDate fromInclusive, LocalDate toInclusive) {
        return repository.findActiveEndingBetween(fromInclusive, toInclusive)
            .stream()
            .map(RentalContractEntity::toDomain)
            .toList();
    }
}
