package com.fixup.jobs.domain;

import java.util.List;
import java.util.UUID;

/** Evidencias y bitácora del trabajo: lo que el cliente revisa para confiar en lo que se hizo. */
public interface JobRecords {
    void addEvidence(JobEvidence evidence);

    List<JobEvidence> evidenceOf(UUID jobId);

    void log(JobLogEntry entry);

    List<JobLogEntry> logOf(UUID jobId);
}
