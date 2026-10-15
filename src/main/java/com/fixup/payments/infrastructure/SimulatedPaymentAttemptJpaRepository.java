package com.fixup.payments.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SimulatedPaymentAttemptJpaRepository extends JpaRepository<SimulatedPaymentAttemptEntity, UUID> {
    List<SimulatedPaymentAttemptEntity> findByPaymentIdOrderByAttemptedAtAsc(UUID paymentId);
}
