package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.fixers.api.Specialty;
import com.fixup.identityaccess.api.*;
import com.fixup.jobs.api.*;
import com.fixup.jobs.domain.*;
import com.fixup.notifications.api.NotificationType;
import com.fixup.payments.api.*;
import com.fixup.properties.api.PropertyWorkHistory;
import com.fixup.requests.api.*;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompleteJobTest {
    private final UUID jobId = UUID.randomUUID(), quotation = UUID.randomUUID(), requestId = UUID.randomUUID(),
            owner = UUID.randomUUID(), fixer = UUID.randomUUID(), property = UUID.randomUUID();
    private final Jobs jobs = mock(Jobs.class);
    private final JobRecords records = mock(JobRecords.class);
    private final FixerEligibility eligibility = mock(FixerEligibility.class);
    private final RepairRequestDirectory requests = mock(RepairRequestDirectory.class);
    private final PaymentSettlement settlement = mock(PaymentSettlement.class);
    private final PropertyWorkHistory history = mock(PropertyWorkHistory.class);
    private final JobNotices notices = mock(JobNotices.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CompleteJob service = new CompleteJob(jobs, records, eligibility, requests, settlement, history, notices, events);
    private final CurrentActor actor = new CurrentActor(fixer, "auth0|fixer", Set.of(Role.FIXER), UserStatus.ACTIVE);

    @Test
    void cashTermsAreForwardedOnceInTransactionOrderWithTheSameCompletionTime() {
        given(job("CASH"));
        service.execute(actor, jobId, 60_000L, "Reparado");
        var completed = ArgumentCaptor.forClass(Job.class);
        var log = ArgumentCaptor.forClass(JobLogEntry.class);
        var financial = ArgumentCaptor.forClass(PaymentSettlementCommand.class);
        var request = ArgumentCaptor.forClass(RepairRequestCompletionCommand.class);
        var event = ArgumentCaptor.forClass(JobCompleted.class);
        var order = inOrder(eligibility, jobs, records, settlement, requests, history, events, notices);
        order.verify(eligibility).requireVerified(actor);
        order.verify(jobs).findByIdForUpdate(jobId);
        order.verify(jobs).update(completed.capture());
        order.verify(records).log(log.capture());
        order.verify(settlement).settle(financial.capture());
        order.verify(requests).complete(request.capture());
        order.verify(requests).require(requestId);
        order.verify(history).recordCompletedWork(any());
        order.verify(events).publishEvent(event.capture());
        order.verify(notices).progressed(eq(completed.getValue()), any(), eq(NotificationType.JOB_COMPLETED), anyString(), anyString());
        var time = completed.getValue().completedAt();
        assertThat(financial.getValue()).isEqualTo(new PaymentSettlementCommand(jobId, quotation, requestId,
                owner, fixer, "CASH", 100_000, 60_000L, time));
        assertThat(request.getValue()).isEqualTo(new RepairRequestCompletionCommand(requestId, owner, fixer, time));
        assertThat(log.getValue().changedAt()).isEqualTo(time);
        assertThat(event.getValue().completedAt()).isEqualTo(time);
        verify(records, times(1)).log(any());
        verify(settlement, times(1)).settle(any());
    }

    @Test
    void financialFailureStopsRequestClosureHistoryAndCompletionEvents() {
        given(job("TRANSFER"));
        doThrow(new PaymentConflictException("TEST_FAILURE", "Settlement rejected")).when(settlement).settle(any());
        assertThatThrownBy(() -> service.execute(actor, jobId)).isInstanceOf(PaymentConflictException.class);
        verify(requests, never()).complete(any());
        verifyNoInteractions(history, events, notices);
    }

    @Test
    void requestFailureDoesNotPublishCompletionOrNotifications() {
        given(job("TRANSFER"));
        doThrow(new RepairRequestConflictException("REQUEST_NOT_ASSIGNED", "Mismatch")).when(requests).complete(any());
        assertThatThrownBy(() -> service.execute(actor, jobId)).isInstanceOf(RepairRequestConflictException.class);
        verify(settlement).settle(any());
        verifyNoInteractions(history, events, notices);
    }

    @Test
    void anotherFixerCannotComplete() {
        given(job("TRANSFER"));
        var other = new CurrentActor(UUID.randomUUID(), "auth0|other", Set.of(Role.FIXER), UserStatus.ACTIVE);
        assertThatThrownBy(() -> service.execute(other, jobId)).isInstanceOf(JobAccessDeniedException.class);
        verifyNoInteractions(records, settlement, history, events, notices);
    }

    @Test
    void ineligibleFixerFailsBeforeLockAndWrites() {
        doThrow(new IllegalStateException("Not verified")).when(eligibility).requireVerified(actor);
        assertThatThrownBy(() -> service.execute(actor, jobId)).hasMessage("Not verified");
        verifyNoInteractions(jobs, records, settlement, history, events, notices);
    }

    @Test
    void missingJobHasNoFinancialEffects() {
        assertThatThrownBy(() -> service.execute(actor, jobId)).isInstanceOf(JobNotFoundException.class);
        verifyNoInteractions(records, settlement, history, events, notices);
    }

    @Test
    void completedJobCannotWriteAnotherLogOrSettlement() {
        given(job("TRANSFER").complete(Instant.parse("2026-10-01T12:10:00Z")));
        assertThatThrownBy(() -> service.execute(actor, jobId)).isInstanceOf(JobConflictException.class);
        verifyNoInteractions(records, settlement, history, events, notices);
    }

    private Job job(String method) {
        return Job.assigned(jobId, requestId, quotation, fixer, owner, method, 100_000, Instant.parse("2026-10-01T12:00:00Z"));
    }

    private void given(Job job) {
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(requests.require(requestId)).thenReturn(new RepairRequestSnapshot(requestId, owner, Specialty.PLUMBING,
                RepairRequestStatus.COMPLETED, fixer, com.fixup.requests.api.PaymentMethod.TRANSFER, property,
                owner, null, "Fuga", "Casa", "Dirección", "Bogotá"));
    }
}
