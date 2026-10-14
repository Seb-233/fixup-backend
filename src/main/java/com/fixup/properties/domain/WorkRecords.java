package com.fixup.properties.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WorkRecords {
    boolean existsByJob(UUID jobId);

    void create(WorkRecord record);

    void updateRating(UUID jobId, int rating);

    List<WorkRecord> findByProperty(UUID propertyId);

    /** Resumen del sello para una página de resultados, en una sola consulta. */
    Map<UUID, SealSummary> sealSummaries(Collection<UUID> propertyIds);
}
