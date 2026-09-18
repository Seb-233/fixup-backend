package com.fixup.fixers.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FixerProfileJpaRepository extends JpaRepository<FixerProfileEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from FixerProfileEntity profile where profile.userId = :userId")
    Optional<FixerProfileEntity> findAndLockByUserId(@Param("userId") UUID userId);
}
