package com.fixup.payments.application;

import com.fixup.jobs.api.JobCompleted;
import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.application.ReleaseEarningOnCompletedJob;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-UC-20: la condición de liberación del escrow es explícita en HELD.
 * AVAILABLE y PAID_OUT no deben intentar liberarse.
 */
class ReleaseEarningOnCompletedJobTest {
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-20T01:00:00Z");

    private FixerEarning earning(EarningStatus status) {
        long gross = 450_000L;
        long commission = 45_000L;
        long net = 405_000L;
        return new FixerEarning(UUID.randomUUID(), QUOTATION, FIXER, gross, commission, net,
                1_000, status, NOW,
                status == EarningStatus.HELD ? null : NOW,
                status == EarningStatus.PAID_OUT ? NOW : null,
                NOW);
    }

    @Test
    void aHeldEarningIsReleasedWhenTheJobCompletes() {
        var earnings = mock(FixerEarnings.class);
        var held = earning(EarningStatus.HELD);
        when(earnings.findByQuotationForUpdate(QUOTATION)).thenReturn(Optional.of(held));

        new ReleaseEarningOnCompletedJob(earnings).on(new JobCompleted(UUID.randomUUID(), QUOTATION, FIXER, NOW));

        verify(earnings).update(any());
    }

    @Test
    void anAvailableEarningIsNotTouchedWhenTheJobEventFires() {
        var earnings = mock(FixerEarnings.class);
        var available = earning(EarningStatus.AVAILABLE);
        when(earnings.findByQuotationForUpdate(QUOTATION)).thenReturn(Optional.of(available));

        assertThatNoException().isThrownBy(() ->
                new ReleaseEarningOnCompletedJob(earnings).on(new JobCompleted(UUID.randomUUID(), QUOTATION, FIXER, NOW)));

        // AVAILABLE no pasa a update: no hay transición válida desde ese estado.
        verify(earnings, never()).update(any());
    }

    @Test
    void aPaidOutEarningIsNotTouchedWhenTheJobEventFires() {
        var earnings = mock(FixerEarnings.class);
        var paidOut = earning(EarningStatus.PAID_OUT);
        when(earnings.findByQuotationForUpdate(QUOTATION)).thenReturn(Optional.of(paidOut));

        assertThatNoException().isThrownBy(() ->
                new ReleaseEarningOnCompletedJob(earnings).on(new JobCompleted(UUID.randomUUID(), QUOTATION, FIXER, NOW)));

        verify(earnings, never()).update(any());
    }

    @Test
    void releasingAHeldEarningProducesAvailableStatus() {
        var held = earning(EarningStatus.HELD);
        var released = held.release(NOW);
        assertThat(released.status()).isEqualTo(EarningStatus.AVAILABLE);
        assertThat(released.releasedAt()).isEqualTo(NOW);
    }

    @Test
    void releasingAnAvailableEarningThrowsDomainException() {
        var available = earning(EarningStatus.AVAILABLE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> available.release(NOW))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("in escrow");
    }

    @Test
    void releasingAPaidOutEarningThrowsDomainException() {
        var paidOut = earning(EarningStatus.PAID_OUT);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> paidOut.release(NOW))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("in escrow");
    }
}
