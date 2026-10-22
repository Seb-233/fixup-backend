package com.fixup.jobs.application;

import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAssignedJobTest {
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void opensAssignedJobWithTheAcceptedQuotationData() {
        var jobs = mock(Jobs.class);
        var records = mock(JobRecords.class);
        when(jobs.existsByQuotation(QUOTATION)).thenReturn(false);

        new OpenAssignedJob(jobs, records).open(command());

        var captured = ArgumentCaptor.forClass(Job.class);
        verify(jobs).create(captured.capture());
        assertThat(captured.getValue().quotationId()).isEqualTo(QUOTATION);
        assertThat(captured.getValue().requestId()).isEqualTo(REQUEST);
        assertThat(captured.getValue().fixerUserId()).isEqualTo(FIXER);
        assertThat(captured.getValue().ownerUserId()).isEqualTo(OWNER);
        var entry = ArgumentCaptor.forClass(JobLogEntry.class);
        verify(records).log(entry.capture());
        assertThat(entry.getValue().jobId()).isEqualTo(captured.getValue().id());
        assertThat(entry.getValue().note()).contains("efectivo");
    }

    @Test
    void repeatedCommandDoesNotCreateAnotherJob() {
        var jobs = mock(Jobs.class);
        when(jobs.existsByQuotation(QUOTATION)).thenReturn(true);

        new OpenAssignedJob(jobs, mock(JobRecords.class)).open(command());

        verify(jobs, never()).create(org.mockito.ArgumentMatchers.any());
    }

    private JobOpeningCommand command() {
        return new JobOpeningCommand(REQUEST, QUOTATION, FIXER, OWNER, "CASH", 100_000L, NOW);
    }
}
