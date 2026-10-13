package com.fixup.payments.infrastructure;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OwnerChargeJpaRepository extends JpaRepository<OwnerChargeEntity, UUID> {
    boolean existsByQuotationId(UUID quotationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select charge from OwnerChargeEntity charge where charge.id = :id")
    Optional<OwnerChargeEntity> findAndLockById(@Param("id") UUID id);

    Optional<OwnerChargeEntity> findFirstByRequestIdOrderByCreatedAtDesc(UUID requestId);

    @Query("""
            select charge from OwnerChargeEntity charge
            where charge.ownerUserId = :userId or charge.managerUserId = :userId
            order by charge.createdAt desc
            """)
    List<OwnerChargeEntity> findByPayer(@Param("userId") UUID userId);

    @Query("""
            select charge from OwnerChargeEntity charge
            where charge.status = com.fixup.payments.api.ChargeStatus.APPROVED
              and charge.approvedAt >= :from and charge.approvedAt < :to
              and (charge.ownerUserId = :userId or charge.managerUserId = :userId)
            order by charge.approvedAt
            """)
    List<OwnerChargeEntity> findApprovedForPayer(@Param("userId") UUID userId, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select charge from OwnerChargeEntity charge
            where charge.status = com.fixup.payments.api.ChargeStatus.APPROVED
              and charge.approvedAt >= :from and charge.approvedAt < :to
            order by charge.approvedAt
            """)
    List<OwnerChargeEntity> findApproved(@Param("from") Instant from, @Param("to") Instant to);
}
