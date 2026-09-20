package com.fixup.payments.infrastructure;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaFixerEarnings implements FixerEarnings {
    private final FixerEarningJpaRepository repository;

    JpaFixerEarnings(FixerEarningJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<FixerEarning> findByQuotationForUpdate(UUID quotationId) {
        return repository.findAndLockByQuotationId(quotationId).map(FixerEarningEntity::toDomain);
    }

    @Override
    public boolean existsByQuotation(UUID quotationId) {
        return repository.existsByQuotationId(quotationId);
    }

    @Override
    public List<FixerEarning> findByFixer(UUID fixerUserId) {
        return repository.findByFixerUserIdOrderByCreatedAtDesc(fixerUserId).stream()
                .map(FixerEarningEntity::toDomain).toList();
    }

    @Override
    public List<FixerEarning> findAvailableByFixerForUpdate(UUID fixerUserId) {
        return repository.findAndLockByFixerUserIdAndStatus(fixerUserId, EarningStatus.AVAILABLE)
                .stream().map(FixerEarningEntity::toDomain).toList();
    }

    @Override
    public void create(FixerEarning earning) {
        repository.saveAndFlush(FixerEarningEntity.from(earning));
    }

    @Override
    public void update(FixerEarning earning) {
        var entity = repository.findAndLockById(earning.id())
                .orElseThrow(() -> new PaymentConflictException("EARNING_NOT_FOUND",
                        "There is no earning to update"));
        entity.apply(earning);
        repository.saveAndFlush(entity);
    }
}
