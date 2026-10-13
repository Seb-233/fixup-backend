package com.fixup.remodelings.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RemodelingRequirementJpaRepository extends JpaRepository<RemodelingRequirementEntity, UUID> {
    List<RemodelingRequirementEntity> findByProjectIdOrderByPosition(UUID projectId);

    List<RemodelingRequirementEntity> findByProjectIdInOrderByProjectIdAscPositionAsc(
            Collection<UUID> projectIds);
}
