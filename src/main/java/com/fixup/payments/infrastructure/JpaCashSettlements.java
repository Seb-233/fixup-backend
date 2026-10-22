package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.CashSettlement;
import com.fixup.payments.domain.CashSettlements;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaCashSettlements implements CashSettlements {
    private final CashSettlementJpaRepository repository;

    JpaCashSettlements(CashSettlementJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CashSettlement> findByJob(UUID jobId) {
        return repository.findByJobId(jobId).map(CashSettlementEntity::toDomain);
    }

    @Override
    public List<CashSettlement> findSettledBetween(UUID ownerUserId, Instant from, Instant to) {
        return repository.findSettledBetween(ownerUserId, from, to).stream()
                .map(CashSettlementEntity::toDomain).toList();
    }

    @Override
    public void create(CashSettlement settlement) {
        repository.saveAndFlush(CashSettlementEntity.from(settlement));
    }
}
