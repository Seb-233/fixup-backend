package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentApproved;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentSimulationOutcome;
import com.fixup.payments.domain.FixerEarning;
import com.fixup.payments.domain.FixerEarnings;
import com.fixup.payments.domain.OwnerDebts;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.RepairPayments;
import com.fixup.payments.domain.SimulatedPaymentAttempt;
import com.fixup.payments.domain.SimulatedPaymentAttempts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulateRepairPaymentTest {
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant NOW = CREATED.plusSeconds(60);
    private final RepairPayments payments = mock(RepairPayments.class);
    private final SimulatedPaymentAttempts attempts = mock(SimulatedPaymentAttempts.class);
    private final FixerEarnings earnings = mock(FixerEarnings.class);
    private final OwnerDebts debts = mock(OwnerDebts.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimulateRepairPayment useCase = new SimulateRepairPayment(payments, attempts,
            earnings, debts, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void pendingDeclinedAndErrorAreAuditedWithoutEconomicEffects() {
        for (var outcome : new PaymentSimulationOutcome[]{PaymentSimulationOutcome.PENDING,
                PaymentSimulationOutcome.DECLINED, PaymentSimulationOutcome.ERROR}) {
            var current = pending();
            when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.of(current));

            var result = useCase.execute(owner(), PAYMENT, outcome);

            assertThat(result.status().name()).isEqualTo(outcome.name());
        }

        var captured = ArgumentCaptor.forClass(SimulatedPaymentAttempt.class);
        verify(attempts, org.mockito.Mockito.times(3)).create(captured.capture());
        assertThat(captured.getAllValues()).extracting(SimulatedPaymentAttempt::requestedOutcome)
                .containsExactly(PaymentStatus.PENDING, PaymentStatus.DECLINED, PaymentStatus.ERROR);
        verify(earnings, never()).create(any());
        verify(events, never()).publishEvent(any(PaymentApproved.class));
    }

    @Test
    void approvalCreatesOneHeldEarningAndPublishesTheExistingEvent() {
        when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.of(pending()));
        when(earnings.existsByQuotation(QUOTATION)).thenReturn(false);

        var result = useCase.execute(owner(), PAYMENT, PaymentSimulationOutcome.APPROVED);

        assertThat(result.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(result.completedAt()).isEqualTo(NOW);
        var earning = ArgumentCaptor.forClass(FixerEarning.class);
        verify(earnings).create(earning.capture());
        assertThat(earning.getValue().grossAmount()).isEqualTo(450_000);
        assertThat(earning.getValue().commissionAmount()).isEqualTo(45_000);
        assertThat(earning.getValue().netAmount()).isEqualTo(405_000);
        assertThat(earning.getValue().status().name()).isEqualTo("HELD");
        var event = ArgumentCaptor.forClass(PaymentApproved.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().paymentId()).isEqualTo(PAYMENT);
        assertThat(event.getValue().quotationId()).isEqualTo(QUOTATION);
    }

    @Test
    void declinedAndErrorCanRetryTheSamePaymentToApproval() {
        for (RepairPayment retryable : new RepairPayment[]{pending().decline(CREATED.plusSeconds(1)),
                pending().fail(CREATED.plusSeconds(1))}) {
            var localPayments = mock(RepairPayments.class);
            var localAttempts = mock(SimulatedPaymentAttempts.class);
            var localEarnings = mock(FixerEarnings.class);
            var localEvents = mock(ApplicationEventPublisher.class);
            when(localPayments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.of(retryable));
            var simulation = new SimulateRepairPayment(localPayments, localAttempts, localEarnings,
                    mock(OwnerDebts.class), localEvents, Clock.fixed(NOW, ZoneOffset.UTC));

            assertThat(simulation.execute(owner(), PAYMENT, PaymentSimulationOutcome.APPROVED).status())
                    .isEqualTo(PaymentStatus.APPROVED);
            verify(localEarnings).create(any(FixerEarning.class));
            verify(localEvents).publishEvent(any(PaymentApproved.class));
        }
    }

    @Test
    void repeatedApprovalIsAnIdempotentReadWithoutAttemptOrEffects() {
        when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.of(pending().approve(CREATED.plusSeconds(1))));

        var result = useCase.execute(owner(), PAYMENT, PaymentSimulationOutcome.APPROVED);

        assertThat(result.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(attempts, never()).create(any());
        verify(payments, never()).update(any());
        verify(earnings, never()).create(any());
        verify(events, never()).publishEvent(any(PaymentApproved.class));
    }

    @Test
    void approvedPaymentCannotBeDegradedAndDoesNotRecordAFailedTransition() {
        when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.of(pending().approve(CREATED.plusSeconds(1))));

        assertThatThrownBy(() -> useCase.execute(owner(), PAYMENT, PaymentSimulationOutcome.DECLINED))
                .isInstanceOf(PaymentConflictException.class)
                .extracting("code").isEqualTo("PAYMENT_ALREADY_APPROVED");
        verify(attempts, never()).create(any());
        verify(payments, never()).update(any());
    }

    @Test
    void onlyTheOwnerCanSimulateAndCashPaymentIsRejected() {
        when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.of(pending()));
        var fixer = new CurrentActor(FIXER, "auth0|fixer", Set.of(Role.FIXER), UserStatus.ACTIVE);
        assertThatThrownBy(() -> useCase.execute(fixer, PAYMENT, PaymentSimulationOutcome.APPROVED))
                .isInstanceOf(com.fixup.payments.api.PaymentAccessDeniedException.class);

        var cashPayment = RepairPayment.pending(PAYMENT, QUOTATION, REQUEST, OWNER, FIXER, 10_000,
                RepairPaymentMethod.CASH, PaymentPurpose.SERVICE, CREATED);
        when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.of(cashPayment));
        assertThatThrownBy(() -> useCase.execute(owner(), PAYMENT, PaymentSimulationOutcome.APPROVED))
                .isInstanceOf(PaymentConflictException.class)
                .extracting("code").isEqualTo("PAYMENT_METHOD_NOT_SUPPORTED");
    }

    private RepairPayment pending() {
        return RepairPayment.pending(PAYMENT, QUOTATION, REQUEST, OWNER, FIXER, 450_000,
                RepairPaymentMethod.TRANSFER, PaymentPurpose.SERVICE, CREATED);
    }

    private CurrentActor owner() {
        return new CurrentActor(OWNER, "auth0|owner", Set.of(Role.OWNER), UserStatus.ACTIVE);
    }
}
