package com.fixup.payments.infrastructure;

import com.fixup.payments.api.OwnerDebtDirectory;
import com.fixup.payments.domain.DebtStatus;
import com.fixup.payments.domain.OwnerDebt;
import com.fixup.payments.domain.OwnerDebts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaOwnerDebts implements OwnerDebts, OwnerDebtDirectory {
    private final OwnerDebtJpaRepository repository;
    private final EntityManager entityManager;

    JpaOwnerDebts(OwnerDebtJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasOutstandingDebtForProperty(UUID propertyId, UUID expectedOwnerUserId) {
        // Uses the same actor-bound connection as request creation. V38 checks authorization
        // inside PostgreSQL; do not fall back to the RLS-filtered owner query if it is unavailable.
        Object result = entityManager.createNativeQuery(
                "SELECT public.app_property_owner_has_outstanding_debt(:propertyId, :ownerUserId)")
                .setParameter("propertyId", propertyId)
                .setParameter("ownerUserId", expectedOwnerUserId)
                .getSingleResult();
        if (!(result instanceof Boolean outstanding)) {
            throw new IllegalStateException("Owner debt check did not return a definite result");
        }
        return outstanding;
    }

    @Override public boolean hasOutstandingDebt(UUID ownerUserId) { return repository.existsByOwnerUserIdAndStatus(ownerUserId, DebtStatus.OUTSTANDING); }
    @Override public Optional<OwnerDebt> findById(UUID id) { return repository.findById(id).map(OwnerDebtEntity::toDomain); }
    @Override public Optional<OwnerDebt> findByIdForUpdate(UUID id) { return repository.findAndLockById(id).map(OwnerDebtEntity::toDomain); }
    @Override public Optional<OwnerDebt> findByQuotationForUpdate(UUID id) { return repository.findAndLockByQuotationId(id).map(OwnerDebtEntity::toDomain); }
    @Override public Optional<OwnerDebt> findByQuotation(UUID id) { return repository.findByQuotationId(id).map(OwnerDebtEntity::toDomain); }
    @Override public List<OwnerDebt> findByOwner(UUID ownerUserId) { return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream().map(OwnerDebtEntity::toDomain).toList(); }
    @Override public void create(OwnerDebt debt) { repository.saveAndFlush(OwnerDebtEntity.from(debt)); }
    @Override public void update(OwnerDebt debt) { var entity = repository.findById(debt.id()).orElseThrow(); entity.apply(debt); repository.saveAndFlush(entity); }
}
