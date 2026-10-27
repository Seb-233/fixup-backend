package com.fixup.payments.application;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentSettlementCommand;
import com.fixup.payments.domain.CashSettlement;
import com.fixup.payments.domain.CashSettlements;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.OwnerDebt;
import com.fixup.payments.domain.OwnerDebts;
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

class SettleCompletedJobTest {
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private final FixerEarnings earnings = mock(FixerEarnings.class);
    private final CashSettlements settlements = mock(CashSettlements.class);
    private final OwnerDebts debts = mock(OwnerDebts.class);
    private final SettleCompletedJob useCase = new SettleCompletedJob(earnings, settlements, debts);

    @Test
    void cashPartialRecordsOnlyMissingBalanceAndNoPlatformEarning() {
        useCase.settle(command("CASH", 60_000L));

        var settled = ArgumentCaptor.forClass(CashSettlement.class);
        var debt = ArgumentCaptor.forClass(OwnerDebt.class);
        verify(settlements).create(settled.capture());
        verify(debts).create(debt.capture());
        assertThat(settled.getValue().agreedAmount()).isEqualTo(100_000);
        assertThat(settled.getValue().receivedAmount()).isEqualTo(60_000);
        assertThat(settled.getValue().outstandingAmount()).isEqualTo(40_000);
        assertThat(debt.getValue().amount()).isEqualTo(40_000);
        verify(earnings, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void transferReleasesOnlyTheMatchingHeldEarning() {
        var held = FixerEarning.held(UUID.randomUUID(), QUOTATION, FIXER, 100_000, NOW);
        when(earnings.findByQuotationForUpdate(QUOTATION)).thenReturn(Optional.of(held));

        useCase.settle(command("TRANSFER", null));

        var released = ArgumentCaptor.forClass(FixerEarning.class);
        verify(earnings).update(released.capture());
        assertThat(released.getValue().status()).isEqualTo(EarningStatus.AVAILABLE);
        assertThat(released.getValue()).isEqualTo(held.release(NOW));
        verify(settlements, never()).create(org.mockito.ArgumentMatchers.any());
        verify(debts, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void transferWithoutEscrowFailsInsteadOfCompletingAnUnfundedJob() {
        assertThatThrownBy(() -> useCase.settle(command("TRANSFER", null)))
                .isInstanceOf(PaymentConflictException.class)
                .extracting("code").isEqualTo("EARNING_NOT_HELD");
    }

    @Test
    void alreadyReleasedEarningIsNotReleasedOrCommissionedAgain() {
        var available = FixerEarning.held(UUID.randomUUID(), QUOTATION, FIXER, 100_000, NOW)
                .release(NOW.minusSeconds(1));
        when(earnings.findByQuotationForUpdate(QUOTATION)).thenReturn(Optional.of(available));
        useCase.settle(command("TRANSFER", null));
        useCase.settle(command("TRANSFER", null));
        verify(earnings, never()).update(org.mockito.ArgumentMatchers.any());
        verify(earnings, never()).create(org.mockito.ArgumentMatchers.any());
        assertThat(available.commissionAmount()).isEqualTo(10_000);
        assertThat(available.netAmount()).isEqualTo(90_000);
    }

    private PaymentSettlementCommand command(String method, Long received) {
        return new PaymentSettlementCommand(JOB, QUOTATION, REQUEST, OWNER, FIXER,
                method, 100_000, received, NOW);
    }
}
