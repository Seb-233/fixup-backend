package com.fixup.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepairPayments {
    Optional<RepairPayment> findById(UUID id);
    Optional<RepairPayment> findByIdForUpdate(UUID id);
    Optional<RepairPayment> findByQuotationAndPurpose(UUID quotationId, PaymentPurpose purpose);
    List<RepairPayment> findByOwner(UUID ownerUserId);
    /** Payments the user owes or may pay as the delegated manager, newest first. */
    List<RepairPayment> findByPayer(UUID userId);
    /** Approved payments between the instants; {@code payerUserId} null means every payer. */
    List<RepairPayment> findApprovedBetween(UUID payerUserId, Instant from, Instant to);
    void create(RepairPayment payment);
    void update(RepairPayment payment);
}
