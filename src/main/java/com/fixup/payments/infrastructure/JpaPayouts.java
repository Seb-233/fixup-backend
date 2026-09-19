package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.Payout;
import com.fixup.payments.domain.Payouts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPayouts implements Payouts {
    private final PayoutJpaRepository repository;

    JpaPayouts(PayoutJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Payout> findByFixer(UUID fixerUserId) {
        return repository.findByFixerUserIdOrderByRequestedAtDesc(fixerUserId).stream()
                .map(PayoutEntity::toDomain).toList();
    }

    @Override
    public void create(Payout payout) {
        repository.saveAndFlush(PayoutEntity.from(payout));
    }
}
