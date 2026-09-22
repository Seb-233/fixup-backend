package com.fixup.requests.infrastructure;

import com.fixup.requests.domain.SlaEventLog;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Repository
class JpaSlaEventLog implements SlaEventLog {
    private final SlaEventLogJpaRepository repository;

    JpaSlaEventLog(SlaEventLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Set<UUID> findRequestIdsAlreadyNotified(Collection<UUID> requestIds, SlaEventType eventType) {
        if (requestIds == null || requestIds.isEmpty()) {
            return Set.of();
        }
        return repository.findRequestIdsWithEvent(requestIds, eventType.name());
    }

    @Override
    public void record(UUID requestId, SlaEventType eventType, Instant at) {
        repository.saveAndFlush(SlaEventLogEntity.of(requestId, eventType.name(), at));
    }
}
