package com.fixup.media.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MediaAssetJpaRepository extends JpaRepository<MediaAssetEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MediaAssetEntity m WHERE m.id = :id")
    Optional<MediaAssetEntity> lockById(@Param("id") UUID id);
}
