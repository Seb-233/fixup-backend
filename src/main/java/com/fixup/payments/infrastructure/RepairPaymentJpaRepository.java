package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentPurpose;
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

    @Query(value = """
            SELECT p.* FROM repair_payments p
            WHERE (p.owner_user_id = :actorUserId
                OR EXISTS (SELECT 1 FROM repair_payment_contexts c
                    JOIN properties property ON property.id = c.property_id
                    WHERE c.quotation_id = p.quotation_id
                      AND c.owner_user_id = p.owner_user_id
                      AND property.owner_user_id = p.owner_user_id
                      AND property.manager_user_id = :actorUserId))
              AND (:requestId IS NULL OR p.request_id = :requestId)
              AND (:status IS NULL OR p.status = :status)
              AND (:purpose IS NULL OR p.purpose = :purpose)
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<RepairPaymentEntity> findVisibleForPayer(@Param("actorUserId") UUID actorUserId,
            @Param("requestId") UUID requestId, @Param("status") String status,
            @Param("purpose") String purpose, @Param("limit") int limit, @Param("offset") int offset);
}
