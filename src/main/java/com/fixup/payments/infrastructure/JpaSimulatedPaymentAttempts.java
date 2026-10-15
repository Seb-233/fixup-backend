package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.SimulatedPaymentAttempt;
import com.fixup.payments.domain.SimulatedPaymentAttempts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaSimulatedPaymentAttempts implements SimulatedPaymentAttempts {
    private final SimulatedPaymentAttemptJpaRepository repository;
    JpaSimulatedPaymentAttempts(SimulatedPaymentAttemptJpaRepository repository) { this.repository = repository; }
    @Override public List<SimulatedPaymentAttempt> findByPayment(UUID paymentId) { return repository.findByPaymentIdOrderByAttemptedAtAsc(paymentId).stream().map(SimulatedPaymentAttemptEntity::toDomain).toList(); }
    @Override public void create(SimulatedPaymentAttempt attempt) { repository.saveAndFlush(SimulatedPaymentAttemptEntity.from(attempt)); }
}
