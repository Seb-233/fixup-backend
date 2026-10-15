package com.fixup.payments.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RepairPaymentJpaRepository extends JpaRepository<RepairPaymentEntity, UUID> {
    Optional<RepairPaymentEntity> findByQuotationId(UUID quotationId);
    List<RepairPaymentEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);
}
