package com.fixup.jobs.application;

import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.jobs.api.JobStatus;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OpenAssignedJobTest {
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @org.junit.jupiter.api.Test
    void cannotOpenNewCashJobEvenWithHistoricalTimestamp() {
        var jobs = mock(Jobs.class);
        var records = mock(JobRecords.class);
        assertThatThrownBy(() -> new OpenAssignedJob(jobs, records).open(command("CASH")))
                .isInstanceOf(com.fixup.payments.api.PaymentConflictException.class);
        verifyNoInteractions(jobs, records);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSFER"})
    void opensAssignedJobAndLogsItOnceWithTheCommandData(String paymentMethod) {
        var jobs = mock(Jobs.class);
        var records = mock(JobRecords.class);
        when(jobs.existsByQuotation(QUOTATION)).thenReturn(false);

        new OpenAssignedJob(jobs, records).open(command(paymentMethod));

        var captured = ArgumentCaptor.forClass(Job.class);
        var capturedLog = ArgumentCaptor.forClass(JobLogEntry.class);
        var order = inOrder(jobs, records);
        order.verify(jobs).existsByQuotation(QUOTATION);
        order.verify(jobs).create(captured.capture());
        order.verify(records).log(capturedLog.capture());
        verifyNoMoreInteractions(jobs, records);

        var job = captured.getValue();
        assertThat(job.id()).isNotNull();
        assertThat(job.quotationId()).isEqualTo(QUOTATION);
        assertThat(job.requestId()).isEqualTo(REQUEST);
        assertThat(job.fixerUserId()).isEqualTo(FIXER);
        assertThat(job.ownerUserId()).isEqualTo(OWNER);
        assertThat(job.status()).isEqualTo(JobStatus.ASSIGNED);
        assertThat(job.paymentMethod()).isEqualTo(paymentMethod);
        assertThat(job.agreedAmount()).isEqualTo(100_000L);
        assertThat(job.createdAt()).isEqualTo(NOW);

        var entry = capturedLog.getValue();
        assertThat(entry.id()).isNotNull();
        assertThat(entry.jobId()).isEqualTo(job.id());
        assertThat(entry.status()).isEqualTo(JobStatus.ASSIGNED);
        assertThat(entry.changedByUserId()).isEqualTo(OWNER);
        assertThat(entry.changedAt()).isEqualTo(job.createdAt());
        assertThat(entry.note()).isEqualTo("Trabajo asignado");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSFER"})
    void repeatedCommandDoesNotCreateAnotherJobOrLog(String paymentMethod) {
        var jobs = mock(Jobs.class);
        var records = mock(JobRecords.class);
        when(jobs.existsByQuotation(QUOTATION)).thenReturn(true);

        new OpenAssignedJob(jobs, records).open(command(paymentMethod));

        verify(jobs).existsByQuotation(QUOTATION);
        verify(jobs, never()).create(any());
        verifyNoInteractions(records);
        verifyNoMoreInteractions(jobs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSFER"})
    void failedJobCreationDoesNotWriteAnInitialLog(String paymentMethod) {
        var jobs = mock(Jobs.class);
        var records = mock(JobRecords.class);
        var failure = new IllegalStateException("Job creation failed");
        when(jobs.existsByQuotation(QUOTATION)).thenReturn(false);
        doThrow(failure).when(jobs).create(any());

        assertThatThrownBy(() -> new OpenAssignedJob(jobs, records).open(command(paymentMethod)))
                .isSameAs(failure);

        var order = inOrder(jobs);
        order.verify(jobs).existsByQuotation(QUOTATION);
        order.verify(jobs).create(any());
        verifyNoInteractions(records);
        verifyNoMoreInteractions(jobs);
    }

    private JobOpeningCommand command(String paymentMethod) {
        return new JobOpeningCommand(REQUEST, QUOTATION, FIXER, OWNER, paymentMethod, 100_000L, NOW);
    }
}
