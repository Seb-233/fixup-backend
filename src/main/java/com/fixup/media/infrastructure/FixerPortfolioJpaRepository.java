package com.fixup.media.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FixerPortfolioJpaRepository extends JpaRepository<FixerPortfolioEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM FixerPortfolioEntity p WHERE p.fixerUserId = :fixerUserId")
    Optional<FixerPortfolioEntity> lockByFixerUserId(@Param("fixerUserId") UUID fixerUserId);
}
