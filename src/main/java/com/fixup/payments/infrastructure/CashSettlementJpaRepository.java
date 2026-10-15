package com.fixup.payments.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CashSettlementJpaRepository extends JpaRepository<CashSettlementEntity, UUID> {
    Optional<CashSettlementEntity> findByJobId(UUID jobId);
}
