package com.fixup.payments.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PayoutJpaRepository extends JpaRepository<PayoutEntity, UUID> {
    List<PayoutEntity> findByFixerUserIdOrderByRequestedAtDesc(UUID fixerUserId);
}
