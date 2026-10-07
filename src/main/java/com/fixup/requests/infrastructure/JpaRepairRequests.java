package com.fixup.requests.infrastructure;

import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaRepairRequests implements RepairRequests {
    private final RepairRequestJpaRepository repository;

    private static final Set<RepairRequestStatus> UNRESOLVED_URGENT_STATUSES = Set.of(
            RepairRequestStatus.OPEN, RepairRequestStatus.ASSIGNED, RepairRequestStatus.SLA_WARNING);

    private static final Set<RepairRequestStatus> SLA_BOARD_STATUSES = Set.of(
            RepairRequestStatus.SLA_WARNING, RepairRequestStatus.SLA_BREACHED);

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
    public List<RepairRequest> findOpenBySpecialties(Collection<Specialty> specialties) {
        if (specialties == null || specialties.isEmpty()) {
            return List.of();
        }
        return repository.findByStatusAndSpecialtyInOrderByCreatedAtDesc(RepairRequestStatus.OPEN, specialties)
                .stream().map(RepairRequestEntity::toDomain).toList();
    }

    @Override
    public List<RepairRequest> findOpenBySpecialtiesAndCity(Collection<Specialty> specialties, String city) {
        if (specialties == null || specialties.isEmpty()) {
            return List.of();
        }
        if (city == null || city.isBlank()) {
            return findOpenBySpecialties(specialties);
        }
        return repository.findByStatusAndSpecialtyInAndPropertyCityOrderByCreatedAtDesc(
                RepairRequestStatus.OPEN, specialties, city)
                .stream().map(RepairRequestEntity::toDomain).toList();
    }

    @Override
    public List<RepairRequest> findUrgentUnresolved() {
        return repository.findByStatusInAndSlaDeadlineNotNullOrderBySlaDeadlineAsc(UNRESOLVED_URGENT_STATUSES)
                .stream().map(RepairRequestEntity::toDomain).toList();
    }

    @Override
    public Page<RepairRequest> findSlaBoardRequests(Pageable pageable) {
        return repository.findByStatusInOrderBySlaDeadlineAscCreatedAtDesc(SLA_BOARD_STATUSES, pageable)
                .map(RepairRequestEntity::toDomain);
    }

    @Override
    @Transactional
    public void updateStatusById(UUID id, RepairRequestStatus status, Instant now) {
        repository.updateStatusById(id, status, now);
    }

    @Override
    public void create(RepairRequest request) {
        repository.saveAndFlush(RepairRequestEntity.from(request));
    }

    @Override
    public void update(RepairRequest request) {
        var entity = repository.findAndLockById(request.id())
                .orElseThrow(RepairRequestNotFoundException::new);
        entity.apply(request);
        repository.saveAndFlush(entity);
    }
}
