package com.fixup.contracts.infrastructure;

import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.domain.Contracts;
import com.fixup.contracts.domain.LeaseContract;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
class JpaContracts implements Contracts {
    private final LeaseContractJpaRepository repository;

    JpaContracts(LeaseContractJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<LeaseContract> findById(UUID id) {
        return repository.findById(id).map(LeaseContractEntity::toDomain);
    }

    @Override
    public Optional<LeaseContract> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).map(LeaseContractEntity::toDomain);
    }

    @Override
    public List<LeaseContract> findByUser(UUID userId, String role, ContractStatus status,
            UUID propertyId, LocalDate startFrom, LocalDate endUntil, int limit) {
        var pageable = limit <= 0 ? PageRequest.of(0, 200) : PageRequest.of(0, limit);
        var safeRole = (role == null) ? "ANY" : role;
        return repository.findByUserFiltered(userId, safeRole, status, propertyId, startFrom,
                endUntil, pageable)
                .stream()
                .map(LeaseContractEntity::toDomain)
                .toList();
    }

    @Override
    public List<LeaseContract> findByProperty(UUID propertyId, ContractStatus status, int limit) {
        var pageable = limit <= 0 ? PageRequest.of(0, 50) : PageRequest.of(0, limit);
        return repository.findByPropertyFiltered(propertyId, status, pageable)
                .stream()
                .map(LeaseContractEntity::toDomain)
                .toList();
    }

    @Override
    public void create(LeaseContract contract) {
        repository.save(LeaseContractEntity.from(contract));
    }

    @Override
    public void update(LeaseContract contract) {
        repository.save(LeaseContractEntity.from(contract));
    }
}
