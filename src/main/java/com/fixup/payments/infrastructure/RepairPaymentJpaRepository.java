package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentPurpose;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepairPaymentJpaRepository extends JpaRepository<RepairPaymentEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from RepairPaymentEntity payment where payment.id = :id")
    Optional<RepairPaymentEntity> findAndLockById(@Param("id") UUID id);

    Optional<RepairPaymentEntity> findByQuotationIdAndPurpose(UUID quotationId, PaymentPurpose purpose);
    List<RepairPaymentEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    @Query("""
            select payment from RepairPaymentEntity payment
            where payment.ownerUserId = :userId or payment.managerUserId = :userId
            order by payment.createdAt desc""")
    List<RepairPaymentEntity> findByPayer(@Param("userId") UUID userId);

    @Query("""
            select payment from RepairPaymentEntity payment
            where payment.status = com.fixup.payments.domain.PaymentStatus.APPROVED
              and payment.completedAt >= :from and payment.completedAt < :to
              and (:payer is null or payment.ownerUserId = :payer or payment.managerUserId = :payer)""")
    List<RepairPaymentEntity> findApprovedBetween(@Param("payer") UUID payer, @Param("from") Instant from,
            @Param("to") Instant to);
}
