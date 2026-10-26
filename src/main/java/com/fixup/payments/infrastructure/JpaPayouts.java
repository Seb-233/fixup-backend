package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.Payout;
import com.fixup.payments.domain.Payouts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPayouts implements Payouts {
    private final PayoutJpaRepository repository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    JpaPayouts(PayoutJpaRepository repository, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
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

    public void lockFixer(UUID fixerUserId) {
        jdbc.queryForObject("SELECT user_id FROM fixer_profiles WHERE user_id = ? FOR UPDATE", UUID.class, fixerUserId);
    }
    public java.util.Optional<Payout> findByOperation(UUID fixerUserId, UUID key) {
        return repository.findByFixerUserIdAndOperationKey(fixerUserId, key).map(PayoutEntity::toDomain);
    }
    public void include(Payout payout, com.fixup.payments.domain.FixerEarning earning) {
        jdbc.update("INSERT INTO payout_entries(payout_id, earning_id, fixer_user_id, net_amount) VALUES (?,?,?,?)",
                payout.id(), earning.id(), payout.fixerUserId(), earning.netAmount());
    }
    public List<com.fixup.payments.domain.PayoutEntry> entries(UUID payoutId) {
        return jdbc.query("SELECT earning_id, net_amount FROM payout_entries WHERE payout_id = ? ORDER BY earning_id",
                (r,n) -> new com.fixup.payments.domain.PayoutEntry(r.getObject("earning_id", UUID.class), r.getLong("net_amount")), payoutId);
    }
}
