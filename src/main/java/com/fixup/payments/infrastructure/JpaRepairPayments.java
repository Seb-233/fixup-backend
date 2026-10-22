package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPayments;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaRepairPayments implements RepairPayments {
    private final RepairPaymentJpaRepository repository;
    JpaRepairPayments(RepairPaymentJpaRepository repository) { this.repository = repository; }
    @Override public Optional<RepairPayment> findById(UUID id) { return repository.findById(id).map(RepairPaymentEntity::toDomain); }
    @Override public Optional<RepairPayment> findByIdForUpdate(UUID id) { return repository.findAndLockById(id).map(RepairPaymentEntity::toDomain); }
    @Override public Optional<RepairPayment> findByQuotationAndPurpose(UUID quotationId, PaymentPurpose purpose) { return repository.findByQuotationIdAndPurpose(quotationId, purpose).map(RepairPaymentEntity::toDomain); }
    @Override public List<RepairPayment> findByOwner(UUID ownerUserId) { return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream().map(RepairPaymentEntity::toDomain).toList(); }
    @Override public List<RepairPayment> findByPayer(UUID userId) { return repository.findByPayer(userId).stream().map(RepairPaymentEntity::toDomain).toList(); }
    @Override public List<RepairPayment> findApprovedBetween(UUID payerUserId, Instant from, Instant to) { return repository.findApprovedBetween(payerUserId, from, to).stream().map(RepairPaymentEntity::toDomain).toList(); }
    @Override public void create(RepairPayment payment) { repository.saveAndFlush(RepairPaymentEntity.from(payment)); }
    @Override public void update(RepairPayment payment) {
        var entity = repository.findById(payment.id()).orElseThrow();
        entity.apply(payment);
        repository.saveAndFlush(entity);
    }
}
