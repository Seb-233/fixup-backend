package com.fixup.jobs.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface Jobs {
    /** Reads the job for a decision, holding the row until the transaction ends. */
    Optional<Job> findByIdForUpdate(UUID id);

    List<Job> findByFixer(UUID fixerUserId);

    Optional<Job> findById(UUID id);

    Optional<Job> findByRequestId(UUID requestId);

    List<Job> findByRequestIds(Collection<UUID> requestIds);

    boolean existsByQuotation(UUID quotationId);

    /** FR-UC-11: trabajos sin cerrar por técnico, la medida de disponibilidad. */
    Map<UUID, Long> countOpenByFixers(Collection<UUID> fixerUserIds);

    void create(Job job);

    void update(Job job);
}
