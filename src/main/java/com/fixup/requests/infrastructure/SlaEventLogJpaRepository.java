package com.fixup.requests.infrastructure;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SlaEventLogJpaRepository extends JpaRepository<SlaEventLogEntity, Long> {

    @Query("SELECT l.requestId FROM SlaEventLogEntity l WHERE l.requestId IN :ids AND l.eventType = :eventType")
    Set<UUID> findRequestIdsWithEvent(@Param("ids") Collection<UUID> ids,
            @Param("eventType") String eventType);
}
