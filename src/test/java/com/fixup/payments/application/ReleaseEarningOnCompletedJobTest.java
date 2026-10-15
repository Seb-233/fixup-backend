package com.fixup.payments.application;

import com.fixup.jobs.api.JobCompleted;
import com.fixup.payments.api.PaymentSettlement;
import com.fixup.payments.api.PaymentSettlementCommand;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReleaseEarningOnCompletedJobTest {
    @Test
    void delegatesTheImmutableJobTermsToFinancialSettlement() {
        var settlement = mock(PaymentSettlement.class);
        var event = new JobCompleted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "CASH", 100_000, 60_000L,
                Instant.parse("2026-10-15T12:00:00Z"));

        new ReleaseEarningOnCompletedJob(settlement).on(event);

        var command = ArgumentCaptor.forClass(PaymentSettlementCommand.class);
        verify(settlement).settle(command.capture());
        assertThat(command.getValue().jobId()).isEqualTo(event.jobId());
        assertThat(command.getValue().cashReceivedAmount()).isEqualTo(event.receivedAmount());
    }
}
