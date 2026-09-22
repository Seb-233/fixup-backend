package com.fixup.requests.infrastructure;

import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaRepairRequests implements RepairRequests {
    private final RepairRequestJpaRepository repository;

    JpaRepairRequests(RepairRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<RepairRequest> findById(UUID id) {
        return repository.findById(id).map(RepairRequestEntity::toDomain);
    }

    @Override
    public Optional<RepairRequest> findByIdForUpdate(UUID id) {
        return repository.findAndLockById(id).map(RepairRequestEntity::toDomain);
    }

    @Override
    public List<RepairRequest> findByOwner(UUID ownerUserId) {
        return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
                .map(RepairRequestEntity::toDomain).toList();
    }

    @Override
    public List<RepairRequest> findOpen(Specialty specialty) {
        var entities = specialty == null
                ? repository.findByStatusOrderByCreatedAtDesc(RepairRequestStatus.OPEN)
                : repository.findByStatusAndSpecialtyOrderByCreatedAtDesc(RepairRequestStatus.OPEN, specialty);
        return entities.stream().map(RepairRequestEntity::toDomain).toList();
    }

    @Override
    public List<RepairRequest> findOpenBySpecialties(java.util.Collection<Specialty> specialties) {
        if (specialties == null || specialties.isEmpty()) {
            return List.of();
        }
        return repository.findByStatusAndSpecialtyInOrderByCreatedAtDesc(RepairRequestStatus.OPEN, specialties)
                .stream().map(RepairRequestEntity::toDomain).toList();
    }

    @Override
    public List<RepairRequest> findActiveWithSlaDeadlineBefore(Instant deadline) {
        return repository.findActiveWithSlaDeadlineBefore(deadline).stream()
                .map(RepairRequestEntity::toDomain).toList();
    }

    @Override
    public void create(RepairRequest request) {
        repository.saveAndFlush(RepairRequestEntity.from(request));
    }

    @Override
    public void update(RepairRequest request) {
        // The row is locked before a decision so two acceptances cannot overwrite each other.
        var entity = repository.findAndLockById(request.id())
                .orElseThrow(RepairRequestNotFoundException::new);
        entity.apply(request);
        repository.saveAndFlush(entity);
    }
}
