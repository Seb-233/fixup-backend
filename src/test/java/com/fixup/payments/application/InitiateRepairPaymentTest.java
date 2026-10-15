package com.fixup.payments.application;

import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentInitiationCommand;
import com.fixup.payments.api.PaymentInitiationResult;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.RepairPayments;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitiateRepairPaymentTest {
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void transferCreatesPendingServicePaymentFromAuthoritativeQuotationData() {
        var payments = mock(RepairPayments.class);
        when(payments.findByQuotationAndPurpose(QUOTATION, PaymentPurpose.SERVICE))
                .thenReturn(Optional.empty());

        var result = new InitiateRepairPayment(payments).initiate(command(450_000, "TRANSFER"));

        var captured = ArgumentCaptor.forClass(RepairPayment.class);
        verify(payments).create(captured.capture());
        var payment = captured.getValue();
        assertThat(result.paymentId()).isEqualTo(payment.id());
        assertThat(result.status()).isEqualTo(PaymentInitiationResult.Status.PENDING);
        assertThat(payment.quotationId()).isEqualTo(QUOTATION);
        assertThat(payment.requestId()).isEqualTo(REQUEST);
        assertThat(payment.ownerUserId()).isEqualTo(OWNER);
        assertThat(payment.fixerUserId()).isEqualTo(FIXER);
        assertThat(payment.amount()).isEqualTo(450_000);
        assertThat(payment.paymentMethod()).isEqualTo(RepairPaymentMethod.TRANSFER);
        assertThat(payment.purpose()).isEqualTo(PaymentPurpose.SERVICE);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void exactRetryReusesExistingPaymentWithoutCreatingAnother() {
        var payments = mock(RepairPayments.class);
        var existing = RepairPayment.pending(UUID.randomUUID(), QUOTATION, REQUEST, OWNER, FIXER,
                450_000, RepairPaymentMethod.TRANSFER, PaymentPurpose.SERVICE, NOW);
        when(payments.findByQuotationAndPurpose(QUOTATION, PaymentPurpose.SERVICE))
                .thenReturn(Optional.of(existing));

        var result = new InitiateRepairPayment(payments).initiate(command(450_000, "TRANSFER"));

        assertThat(result.paymentId()).isEqualTo(existing.id());
        verify(payments, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryWithDifferentImmutableDataIsAControlledConflict() {
        var payments = mock(RepairPayments.class);
        var existing = RepairPayment.pending(UUID.randomUUID(), QUOTATION, REQUEST, OWNER, FIXER,
                450_000, RepairPaymentMethod.TRANSFER, PaymentPurpose.SERVICE, NOW);
        when(payments.findByQuotationAndPurpose(QUOTATION, PaymentPurpose.SERVICE))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> new InitiateRepairPayment(payments)
                .initiate(command(460_000, "TRANSFER")))
                .isInstanceOf(PaymentConflictException.class)
                .extracting("code").isEqualTo("PAYMENT_INITIATION_CONFLICT");
        verify(payments, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cashDoesNotCreateAGatewayPayment() {
        var payments = mock(RepairPayments.class);

        assertThatThrownBy(() -> new InitiateRepairPayment(payments)
                .initiate(command(450_000, "CASH")))
                .isInstanceOf(PaymentConflictException.class)
                .extracting("code").isEqualTo("CASH_PAYMENT_NOT_INITIATED");
        verify(payments, never()).create(org.mockito.ArgumentMatchers.any());
    }

    private PaymentInitiationCommand command(long amount, String method) {
        return new PaymentInitiationCommand(QUOTATION, REQUEST, OWNER, FIXER, amount, method, NOW);
    }
}
