package com.fixup.jobs.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JobEvidenceJpaRepository extends JpaRepository<JobEvidenceEntity, UUID> {
    List<JobEvidenceEntity> findByJobIdOrderByCreatedAtAsc(UUID jobId);
}
