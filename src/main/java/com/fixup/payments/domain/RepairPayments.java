package com.fixup.payments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepairPayments {
    Optional<RepairPayment> findById(UUID id);
    Optional<RepairPayment> findByIdForUpdate(UUID id);
    Optional<RepairPayment> findByQuotationAndPurpose(UUID quotationId, PaymentPurpose purpose);
    List<RepairPayment> findByOwner(UUID ownerUserId);
    RepairPaymentPage findVisibleForPayer(UUID actorUserId, UUID requestId, PaymentStatus status,
            PaymentPurpose purpose, int page, int size);
    void create(RepairPayment payment);
    void update(RepairPayment payment);
}
