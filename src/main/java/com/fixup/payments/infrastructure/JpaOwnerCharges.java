package com.fixup.payments.infrastructure;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.OwnerCharge;
import com.fixup.payments.domain.OwnerCharges;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaOwnerCharges implements OwnerCharges {
    private final OwnerChargeJpaRepository repository;

    JpaOwnerCharges(OwnerChargeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByQuotation(UUID quotationId) {
        return repository.existsByQuotationId(quotationId);
    }

    @Override
    public void create(OwnerCharge charge) {
        repository.saveAndFlush(OwnerChargeEntity.from(charge));
    }

    @Override
    public void update(OwnerCharge charge) {
        var entity = repository.findAndLockById(charge.id())
                .orElseThrow(() -> new PaymentConflictException("CHARGE_NOT_FOUND", "The charge does not exist"));
        entity.apply(charge);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<OwnerCharge> findById(UUID id) {
        return repository.findById(id).map(OwnerChargeEntity::toDomain);
    }

    @Override
    public Optional<OwnerCharge> findByIdForUpdate(UUID id) {
        return repository.findAndLockById(id).map(OwnerChargeEntity::toDomain);
    }

    @Override
    public Optional<OwnerCharge> findByRequest(UUID requestId) {
        return repository.findFirstByRequestIdOrderByCreatedAtDesc(requestId).map(OwnerChargeEntity::toDomain);
    }

    @Override
    public List<OwnerCharge> findByPayer(UUID userId) {
        return repository.findByPayer(userId).stream().map(OwnerChargeEntity::toDomain).toList();
    }

    @Override
    public List<OwnerCharge> findApprovedBetween(UUID payerOrNull, Instant from, Instant to) {
        var rows = payerOrNull == null ? repository.findApproved(from, to)
                : repository.findApprovedForPayer(payerOrNull, from, to);
        return rows.stream().map(OwnerChargeEntity::toDomain).toList();
    }
}
