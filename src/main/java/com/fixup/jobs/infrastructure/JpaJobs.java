package com.fixup.jobs.infrastructure;

import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.Jobs;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaJobs implements Jobs {
    private final JobJpaRepository repository;

    JpaJobs(JobJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Job> findByIdForUpdate(UUID id) {
        return repository.findAndLockById(id).map(JobEntity::toDomain);
    }

    @Override
    public List<Job> findByFixer(UUID fixerUserId) {
        return repository.findByFixerUserIdOrderByCreatedAtDesc(fixerUserId).stream()
                .map(JobEntity::toDomain).toList();
    }

    @Override
    public Optional<Job> findById(UUID id) {
        return repository.findById(id).map(JobEntity::toDomain);
    }

    @Override
    public Optional<Job> findByRequestId(UUID requestId) {
        return repository.findFirstByRequestIdOrderByCreatedAtDesc(requestId).map(JobEntity::toDomain);
    }

    @Override
    public List<Job> findByRequestIds(Collection<UUID> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return List.of();
        }
        return repository.findByRequestIdIn(requestIds).stream().map(JobEntity::toDomain).toList();
    }

    @Override
    public boolean existsByQuotation(UUID quotationId) {
        return repository.existsByQuotationId(quotationId);
    }

    @Override
    public Map<UUID, Long> countOpenByFixers(Collection<UUID> fixerUserIds) {
        if (fixerUserIds == null || fixerUserIds.isEmpty()) {
            return Map.of();
        }
        var loads = new HashMap<UUID, Long>();
        repository.countOpenByFixers(fixerUserIds).forEach(load -> loads.put(load.getFixerUserId(), load.getOpenJobs()));
        return loads;
    }

    @Override
    public void create(Job job) {
        repository.saveAndFlush(JobEntity.from(job));
    }

    @Override
    public void update(Job job) {
        var entity = repository.findAndLockById(job.id()).orElseThrow(JobNotFoundException::new);
        entity.apply(job);
        repository.saveAndFlush(entity);
    }
}
