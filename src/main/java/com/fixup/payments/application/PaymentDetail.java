package com.fixup.payments.application;

import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Authoritative public-facing view of a simulated payment. */
@Schema(requiredProperties = {"paymentId", "quotationId", "requestId", "amount", "paymentMethod",
        "purpose", "status", "createdAt", "updatedAt"})
public record PaymentDetail(UUID paymentId, UUID quotationId, UUID requestId, long amount,
        RepairPaymentMethod paymentMethod, PaymentPurpose purpose, PaymentStatus status,
        Instant createdAt, Instant updatedAt,
        @Schema(types = {"string", "null"}) Instant completedAt) {

    static PaymentDetail from(RepairPayment payment) {
        return new PaymentDetail(payment.id(), payment.quotationId(), payment.requestId(), payment.amount(),
                payment.paymentMethod(), payment.purpose(), payment.status(), payment.createdAt(),
                payment.updatedAt(), payment.completedAt());
    }
}
