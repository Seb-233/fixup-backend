package com.fixup.jobs.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JobLogJpaRepository extends JpaRepository<JobLogEntity, UUID> {
    List<JobLogEntity> findByJobIdOrderByChangedAtAsc(UUID jobId);
}
