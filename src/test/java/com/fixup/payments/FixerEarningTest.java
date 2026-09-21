package com.fixup.payments;

import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PayoutStatus;
import com.fixup.payments.domain.CommissionPolicy;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.Payout;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-UC-20: aritmÃ©tica de la comisiÃ³n y ciclo del escrow, sin contexto de Spring. */
class FixerEarningTest {
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-19T15:00:00Z");

    private FixerEarning held(long gross) {
        return FixerEarning.held(UUID.randomUUID(), QUOTATION, FIXER, gross, NOW);
    }

    @Test
    void theCommissionIsTenPercentOfTheGrossAmount() {
        assertThat(CommissionPolicy.RATE_BASIS_POINTS).isEqualTo(1_000);
        assertThat(CommissionPolicy.commissionFor(450_000L)).isEqualTo(45_000L);
        assertThat(CommissionPolicy.netFor(450_000L)).isEqualTo(405_000L);
    }

    @Test
    void anIndivisibleRemainderStaysWithTheFixerAndNotWithThePlatform() {
        // 10% de 999 son 99,9: la divisiÃ³n entera trunca y el peso suelto queda del lado del tÃ©cnico.
        assertThat(CommissionPolicy.commissionFor(999L)).isEqualTo(99L);
        assertThat(CommissionPolicy.netFor(999L)).isEqualTo(900L);
        assertThat(CommissionPolicy.commissionFor(999L) + CommissionPolicy.netFor(999L))
                .isEqualTo(999L);
    }

    @Test
    void theCommissionAndTheNetAlwaysAddUpToTheGross() {
        for (long gross : new long[]{1L, 7L, 99L, 100L, 12_345L, 450_000L, 9_999_999L}) {
            var earning = held(gross);
            assertThat(earning.commissionAmount() + earning.netAmount()).isEqualTo(gross);
        }
    }

    @Test
    void anEarningStartsHeldInEscrowWithTheRateFrozen() {
        var earning = held(450_000L);
        assertThat(earning.status()).isEqualTo(EarningStatus.HELD);
        assertThat(earning.releasedAt()).isNull();
        assertThat(earning.paidOutAt()).isNull();
        assertThat(earning.commissionRateBasisPoints()).isEqualTo(CommissionPolicy.RATE_BASIS_POINTS);
    }

    @Test
    void closingTheJobReleasesTheMoneyWithoutRepricingIt() {
        var released = held(450_000L).release(NOW);
        assertThat(released.status()).isEqualTo(EarningStatus.AVAILABLE);
        assertThat(released.releasedAt()).isEqualTo(NOW);
        assertThat(released.netAmount()).isEqualTo(405_000L);
        assertThat(released.commissionAmount()).isEqualTo(45_000L);
    }

    @Test
    void moneyStillInEscrowCannotBeTransferred() {
        var earning = held(450_000L);
        assertThatThrownBy(() -> earning.payOut(NOW))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("available earning");
    }

    @Test
    void theSameEarningCannotBeReleasedOrTransferredTwice() {
        var released = held(450_000L).release(NOW);
        assertThatThrownBy(() -> released.release(NOW))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("in escrow");

        var paidOut = released.payOut(NOW);
        assertThat(paidOut.status()).isEqualTo(EarningStatus.PAID_OUT);
        assertThatThrownBy(() -> paidOut.payOut(NOW))
                .isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void anEarningNeedsAPositiveGrossAmount() {
        assertThatThrownBy(() -> held(0L))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> held(-1L)).isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void anEarningThatDoesNotAddUpCannotBeBuiltAtAll() {
        assertThatThrownBy(() -> new FixerEarning(UUID.randomUUID(), QUOTATION, FIXER, 100L, 10L,
                50L, 1_000, EarningStatus.HELD, NOW, null, null, NOW))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("add up");
    }

    @Test
    void aTransferWithoutAvailableBalanceIsRejected() {
        assertThatThrownBy(() -> Payout.requested(UUID.randomUUID(), FIXER, 0L, 0, NOW))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("no available balance");
    }

    @Test
    void aTransferRecordsHowMuchAndOverHowManyServices() {
        var payout = Payout.requested(UUID.randomUUID(), FIXER, 810_000L, 2, NOW);
        assertThat(payout.amount()).isEqualTo(810_000L);
        assertThat(payout.earningCount()).isEqualTo(2);
        assertThat(payout.requestedAt()).isEqualTo(NOW);
    }

    @Test
    void aPayoutKeepsStatusRequested() {
        var payout = Payout.requested(UUID.randomUUID(), FIXER, 810_000L, 2, NOW);
        assertThat(payout.status()).isEqualTo(PayoutStatus.REQUESTED);
    }

    @Test
    void aConsumedEarningKeepsPaidOutStatus() {
        var paidOut = held(450_000L).release(NOW).payOut(NOW);
        assertThat(paidOut.status()).isEqualTo(EarningStatus.PAID_OUT);
        assertThat(paidOut.paidOutAt()).isEqualTo(NOW);
    }
}
