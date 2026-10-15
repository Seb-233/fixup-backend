package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.DebtStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OwnerDebtJpaRepository extends JpaRepository<OwnerDebtEntity, UUID> {
    boolean existsByOwnerUserIdAndStatus(UUID ownerUserId, DebtStatus status);
    List<OwnerDebtEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
}
