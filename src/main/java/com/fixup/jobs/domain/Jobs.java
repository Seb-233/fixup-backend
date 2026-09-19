package com.fixup.jobs.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Jobs {
    /** Reads the job for a decision, holding the row until the transaction ends. */
    Optional<Job> findByIdForUpdate(UUID id);

    List<Job> findByFixer(UUID fixerUserId);

    boolean existsByQuotation(UUID quotationId);

    void create(Job job);

    void update(Job job);
}
