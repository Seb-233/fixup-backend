package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPayments;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaRepairPayments implements RepairPayments {
    private final RepairPaymentJpaRepository repository;
    JpaRepairPayments(RepairPaymentJpaRepository repository) { this.repository = repository; }
    @Override public Optional<RepairPayment> findById(UUID id) { return repository.findById(id).map(RepairPaymentEntity::toDomain); }
    @Override public Optional<RepairPayment> findByQuotation(UUID quotationId) { return repository.findByQuotationId(quotationId).map(RepairPaymentEntity::toDomain); }
    @Override public List<RepairPayment> findByOwner(UUID ownerUserId) { return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream().map(RepairPaymentEntity::toDomain).toList(); }
    @Override public void create(RepairPayment payment) { repository.saveAndFlush(RepairPaymentEntity.from(payment)); }
    @Override public void update(RepairPayment payment) {
        var entity = repository.findById(payment.id()).orElseThrow();
        entity.apply(payment);
        repository.saveAndFlush(entity);
    }
}
