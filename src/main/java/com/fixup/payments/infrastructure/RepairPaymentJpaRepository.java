package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentPurpose;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RepairPaymentJpaRepository extends JpaRepository<RepairPaymentEntity, UUID> {
    Optional<RepairPaymentEntity> findByQuotationIdAndPurpose(UUID quotationId, PaymentPurpose purpose);
    List<RepairPaymentEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
}
