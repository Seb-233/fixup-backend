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
        @Schema(types = {"string", "null"}) Instant completedAt,
        String reference,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.fixup.payments.api.PaymentContext context) {

    static PaymentDetail from(RepairPayment payment) {
        return from(payment, null);
    }

    static PaymentDetail from(RepairPayment payment, com.fixup.payments.api.PaymentContext context) {
        return new PaymentDetail(payment.id(), payment.quotationId(), payment.requestId(), payment.amount(),
                payment.paymentMethod(), payment.purpose(), payment.status(), payment.createdAt(),
                payment.updatedAt(), payment.completedAt(), payment.reference(), context);
    }
}
