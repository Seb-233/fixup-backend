package com.fixup.quotations.application;

import com.fixup.jobs.api.JobOpening;
import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.payments.api.PaymentApproved;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenJobOnPaymentApprovedTest {
    @Test
    void approvedPaymentRoutesExactlyOneAssignedJobCommand() {
        var jobs = mock(JobOpening.class);
        var payment = UUID.randomUUID();
        var quotation = UUID.randomUUID();
        var request = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var fixer = UUID.randomUUID();
        var now = Instant.parse("2026-10-15T12:00:00Z");

        new OpenJobOnPaymentApproved(jobs).on(new PaymentApproved(payment, quotation, request,
                owner, fixer, 450_000, now));

        var captured = ArgumentCaptor.forClass(JobOpeningCommand.class);
        verify(jobs).open(captured.capture());
        assertThat(captured.getValue()).isEqualTo(new JobOpeningCommand(request, quotation, fixer, owner, now));
    }
}
