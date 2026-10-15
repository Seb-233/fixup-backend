package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.DebtStatus;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OwnerDebtJpaRepository extends JpaRepository<OwnerDebtEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select debt from OwnerDebtEntity debt where debt.id = :id")
    java.util.Optional<OwnerDebtEntity> findAndLockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select debt from OwnerDebtEntity debt where debt.quotationId = :quotationId")
    java.util.Optional<OwnerDebtEntity> findAndLockByQuotationId(@Param("quotationId") UUID quotationId);
    java.util.Optional<OwnerDebtEntity> findByQuotationId(UUID quotationId);
    boolean existsByOwnerUserIdAndStatus(UUID ownerUserId, DebtStatus status);
    List<OwnerDebtEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
}
