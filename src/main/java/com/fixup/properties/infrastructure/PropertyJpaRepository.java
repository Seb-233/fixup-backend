package com.fixup.properties.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
interface PropertyJpaRepository extends JpaRepository<PropertyEntity, UUID> {
    List<PropertyEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
}
