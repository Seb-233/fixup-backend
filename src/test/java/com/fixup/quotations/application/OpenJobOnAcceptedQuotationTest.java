package com.fixup.quotations.application;

import com.fixup.jobs.api.JobOpening;
import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.requests.api.PaymentMethod;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OpenJobOnAcceptedQuotationTest {
    private static final UUID QUOTATION = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void cashRoutesAnAssignedJobCommand() {
        var jobs = mock(JobOpening.class);

        new OpenJobOnAcceptedQuotation(jobs).on(event(PaymentMethod.CASH));

        var captured = ArgumentCaptor.forClass(JobOpeningCommand.class);
        verify(jobs).open(captured.capture());
        assertThat(captured.getValue().quotationId()).isEqualTo(QUOTATION);
        assertThat(captured.getValue().requestId()).isEqualTo(REQUEST);
        assertThat(captured.getValue().fixerUserId()).isEqualTo(FIXER);
        assertThat(captured.getValue().ownerUserId()).isEqualTo(OWNER);
        assertThat(captured.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void transferDoesNotOpenAJob() {
        var jobs = mock(JobOpening.class);

        new OpenJobOnAcceptedQuotation(jobs).on(event(PaymentMethod.TRANSFER));

        verify(jobs, never()).open(org.mockito.ArgumentMatchers.any());
    }

    private QuotationAccepted event(PaymentMethod method) {
        return new QuotationAccepted(QUOTATION, REQUEST, FIXER, OWNER, 450_000, NOW, method);
    }
}
