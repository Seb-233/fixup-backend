package com.fixup.payments.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CashSettlementJpaRepository extends JpaRepository<CashSettlementEntity, UUID> {
    Optional<CashSettlementEntity> findByJobId(UUID jobId);

    @Query("""
            select settlement from CashSettlementEntity settlement
            where settlement.settledAt >= :from and settlement.settledAt < :to
              and (:owner is null or settlement.ownerUserId = :owner)""")
    List<CashSettlementEntity> findSettledBetween(@Param("owner") UUID owner, @Param("from") Instant from,
            @Param("to") Instant to);
}
