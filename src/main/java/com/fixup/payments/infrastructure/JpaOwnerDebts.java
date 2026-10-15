package com.fixup.payments.infrastructure;

import com.fixup.payments.api.OwnerDebtDirectory;
import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebt;
import com.fixup.payments.domain.OwnerDebts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaOwnerDebts implements OwnerDebts, OwnerDebtDirectory {
    private final OwnerDebtJpaRepository repository;
    JpaOwnerDebts(OwnerDebtJpaRepository repository) { this.repository = repository; }
    @Override public boolean hasOutstandingDebt(UUID ownerUserId) { return repository.existsByOwnerUserIdAndStatus(ownerUserId, DebtStatus.OUTSTANDING); }
    @Override public Optional<OwnerDebt> findById(UUID id) { return repository.findById(id).map(OwnerDebtEntity::toDomain); }
    @Override public Optional<OwnerDebt> findByIdForUpdate(UUID id) { return repository.findAndLockById(id).map(OwnerDebtEntity::toDomain); }
    @Override public Optional<OwnerDebt> findByQuotationForUpdate(UUID id) { return repository.findAndLockByQuotationId(id).map(OwnerDebtEntity::toDomain); }
    @Override public Optional<OwnerDebt> findByQuotation(UUID id) { return repository.findByQuotationId(id).map(OwnerDebtEntity::toDomain); }
    @Override public List<OwnerDebt> findByOwner(UUID ownerUserId) { return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream().map(OwnerDebtEntity::toDomain).toList(); }
    @Override public void create(OwnerDebt debt) { repository.saveAndFlush(OwnerDebtEntity.from(debt)); }
    @Override public void update(OwnerDebt debt) { var entity = repository.findById(debt.id()).orElseThrow(); entity.apply(debt); repository.saveAndFlush(entity); }
}
