package com.fixup.jobs.infrastructure;

import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.Jobs;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

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
    public boolean existsByQuotation(UUID quotationId) {
        return repository.existsByQuotationId(quotationId);
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
