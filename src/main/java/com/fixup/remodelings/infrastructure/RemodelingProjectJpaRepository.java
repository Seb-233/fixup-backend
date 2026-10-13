package com.fixup.remodelings.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RemodelingProjectJpaRepository extends JpaRepository<RemodelingProjectEntity, UUID> {
    List<RemodelingProjectEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
}
