package com.fixup.properties.infrastructure;

import com.fixup.properties.domain.SealSummary;
import com.fixup.properties.domain.WorkRecord;
import com.fixup.properties.domain.WorkRecords;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class JpaWorkRecords implements WorkRecords {
    private final PropertyWorkRecordJpaRepository repository;

    JpaWorkRecords(PropertyWorkRecordJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByJob(UUID jobId) {
        return repository.existsByJobId(jobId);
    }

    @Override
    public void create(WorkRecord record) {
        repository.saveAndFlush(PropertyWorkRecordEntity.from(record));
    }

    @Override
    public void updateRating(UUID jobId, int rating) {
        repository.updateRating(jobId, rating);
    }

    @Override
    public List<WorkRecord> findByProperty(UUID propertyId) {
        return repository.findByPropertyIdOrderByCompletedAtDesc(propertyId).stream()
                .map(PropertyWorkRecordEntity::toDomain).toList();
    }

    @Override
    public Map<UUID, SealSummary> sealSummaries(Collection<UUID> propertyIds) {
        if (propertyIds == null || propertyIds.isEmpty()) {
            return Map.of();
        }
        var since = Instant.now().minus(SealSummary.WINDOW);
        var result = new HashMap<UUID, SealSummary>();
        for (var row : repository.sealRows(propertyIds, since)) {
            result.put(row.getPropertyId(),
                    new SealSummary((int) row.getRecentWorks(), row.getAverageRating(), row.getLastWorkAt()));
        }
        return result;
    }
}
