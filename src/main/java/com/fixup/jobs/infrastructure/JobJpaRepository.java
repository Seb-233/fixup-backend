package com.fixup.jobs.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JobJpaRepository extends JpaRepository<JobEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from JobEntity job where job.id = :id")
    Optional<JobEntity> findAndLockById(@Param("id") UUID id);

    List<JobEntity> findByFixerUserIdOrderByCreatedAtDesc(UUID fixerUserId);

    List<JobEntity> findByRequestIdIn(Collection<UUID> requestIds);

    boolean existsByQuotationId(UUID quotationId);

    Optional<JobEntity> findFirstByRequestIdOrderByCreatedAtDesc(UUID requestId);

    interface FixerLoad {
        UUID getFixerUserId();
        long getOpenJobs();
    }

    @Query("select job.fixerUserId as fixerUserId, count(job) as openJobs from JobEntity job "
            + "where job.fixerUserId in :fixerUserIds and job.status <> com.fixup.jobs.api.JobStatus.COMPLETED "
            + "group by job.fixerUserId")
    List<FixerLoad> countOpenByFixers(@Param("fixerUserIds") Collection<UUID> fixerUserIds);
}
