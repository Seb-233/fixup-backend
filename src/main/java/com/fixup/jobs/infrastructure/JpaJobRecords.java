package com.fixup.jobs.infrastructure;

import com.fixup.jobs.domain.JobEvidence;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaJobRecords implements JobRecords {
    private final JobEvidenceJpaRepository evidences;
    private final JobLogJpaRepository log;

    JpaJobRecords(JobEvidenceJpaRepository evidences, JobLogJpaRepository log) {
        this.evidences = evidences;
        this.log = log;
    }

    @Override
    public void addEvidence(JobEvidence evidence) {
        evidences.saveAndFlush(JobEvidenceEntity.from(evidence));
    }

    @Override
    public List<JobEvidence> evidenceOf(UUID jobId) {
        return evidences.findByJobIdOrderByCreatedAtAsc(jobId).stream().map(JobEvidenceEntity::toDomain).toList();
    }

    @Override
    public void log(JobLogEntry entry) {
        log.saveAndFlush(JobLogEntity.from(entry));
    }

    @Override
    public List<JobLogEntry> logOf(UUID jobId) {
        return log.findByJobIdOrderByChangedAtAsc(jobId).stream().map(JobLogEntity::toDomain).toList();
    }
}
