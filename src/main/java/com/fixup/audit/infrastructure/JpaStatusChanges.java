package com.fixup.audit.infrastructure;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.domain.StatusChangeEntry;
import com.fixup.audit.domain.StatusChanges;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
class JpaStatusChanges implements StatusChanges {
    private final StatusChangeJpaRepository repository;

    JpaStatusChanges(StatusChangeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(StatusChangeEntry entry) {
        repository.save(toEntity(entry));
    }

    @Override
    public List<StatusChangeEntry> findRecent(Optional<AuditedResourceType> resourceType,
            Optional<UUID> resourceId, int limit) {
        var pageable = PageRequest.of(0, limit);
        if (resourceType.isPresent() && resourceId.isPresent()) {
            return repository.findByResourceTypeAndResourceIdOrderByChangedAtDescIdDesc(
                            resourceType.orElseThrow(), resourceId.orElseThrow(), pageable).stream()
                    .map(this::toDomain)
                    .toList();
        }
        if (resourceType.isPresent()) {
            return repository.findByResourceTypeOrderByChangedAtDescIdDesc(
                            resourceType.orElseThrow(), pageable).stream()
                    .map(this::toDomain)
                    .toList();
        }
        return repository.findAllByOrderByChangedAtDescIdDesc(pageable).stream()
                .map(this::toDomain)
                .toList();
    }

    private StatusChangeEntity toEntity(StatusChangeEntry entry) {
        return new StatusChangeEntity(entry.id(), entry.resourceType(), entry.resourceId(),
                entry.previousStatus(), entry.newStatus(), entry.changedByUserId(),
                entry.changedBySubject(), entry.changedAt());
    }

    private StatusChangeEntry toDomain(StatusChangeEntity entity) {
        return new StatusChangeEntry(entity.id(), entity.resourceType(), entity.resourceId(),
                entity.previousStatus(), entity.newStatus(), entity.changedByUserId(),
                entity.changedBySubject(), entity.changedAt());
    }
}
