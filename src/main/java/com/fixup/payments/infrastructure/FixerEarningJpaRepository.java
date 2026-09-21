package com.fixup.payments.infrastructure;

import com.fixup.payments.api.EarningStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FixerEarningJpaRepository extends JpaRepository<FixerEarningEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select earning from FixerEarningEntity earning where earning.quotationId = :quotationId")
    Optional<FixerEarningEntity> findAndLockByQuotationId(@Param("quotationId") UUID quotationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select earning from FixerEarningEntity earning "
            + "where earning.fixerUserId = :fixerUserId and earning.status = :status "
            + "order by earning.createdAt")
    List<FixerEarningEntity> findAndLockByFixerUserIdAndStatus(@Param("fixerUserId") UUID fixerUserId,
            @Param("status") EarningStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select earning from FixerEarningEntity earning where earning.id = :id")
    Optional<FixerEarningEntity> findAndLockById(@Param("id") UUID id);

    boolean existsByQuotationId(UUID quotationId);

    List<FixerEarningEntity> findByFixerUserIdOrderByCreatedAtDesc(UUID fixerUserId);
}
