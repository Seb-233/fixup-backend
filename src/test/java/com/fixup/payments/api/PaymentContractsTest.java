package com.fixup.payments.api;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentContractsTest {
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    void initiationCarriesTheAcceptedQuotationDataWithoutModuleLookups() {
        var command = new PaymentInitiationCommand(QUOTATION, REQUEST, OWNER, FIXER,
                450_000L, "TRANSFER", NOW);

        assertThat(command.quotationId()).isEqualTo(QUOTATION);
        assertThat(command.requestId()).isEqualTo(REQUEST);
        assertThat(command.ownerUserId()).isEqualTo(OWNER);
        assertThat(command.fixerUserId()).isEqualTo(FIXER);
        assertThat(command.amount()).isEqualTo(450_000L);
        assertThat(command.paymentMethod()).isEqualTo("TRANSFER");
        assertThat(command.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void initiationAcceptsOnlyTheCanonicalRequestPaymentMethodNames() {
        assertThat(new PaymentInitiationCommand(QUOTATION, REQUEST, OWNER, FIXER,
                450_000L, "CASH", NOW).paymentMethod()).isEqualTo("CASH");

        assertThatThrownBy(() -> new PaymentInitiationCommand(QUOTATION, REQUEST, OWNER,
                FIXER, 450_000L, "CARD", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRANSFER or CASH");
    }

    @Test
    void settlementCarriesCashInformationOnlyWhenTheFixerReportsIt() {
        var transfer = new PaymentSettlementCommand(JOB, QUOTATION, REQUEST, OWNER, FIXER,
                "TRANSFER", 450_000L, null, NOW);
        var cash = new PaymentSettlementCommand(JOB, QUOTATION, REQUEST, OWNER, FIXER,
                "CASH", 450_000L, 440_000L, NOW);

        assertThat(transfer.cashReceivedAmount()).isNull();
        assertThat(cash.cashReceivedAmount()).isEqualTo(440_000L);
        assertThatThrownBy(() -> new PaymentSettlementCommand(JOB, QUOTATION, REQUEST, OWNER,
                FIXER, "CASH", 450_000L, -1L, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedEventCarriesEverythingJobsNeedsToOpenOneJob() {
        var event = new PaymentApproved(PAYMENT, QUOTATION, REQUEST, OWNER, FIXER,
                450_000L, NOW);

        assertThat(event.paymentId()).isEqualTo(PAYMENT);
        assertThat(event.quotationId()).isEqualTo(QUOTATION);
        assertThat(event.requestId()).isEqualTo(REQUEST);
        assertThat(event.ownerUserId()).isEqualTo(OWNER);
        assertThat(event.fixerUserId()).isEqualTo(FIXER);
        assertThat(event.amount()).isEqualTo(450_000L);
        assertThat(event.approvedAt()).isEqualTo(NOW);
    }
}
