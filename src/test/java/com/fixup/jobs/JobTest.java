package com.fixup.jobs;

import com.fixup.jobs.api.JobAccessDeniedException;
import com.fixup.jobs.api.JobConflictException;
import com.fixup.jobs.api.JobStatus;
import com.fixup.jobs.domain.Job;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-UC-20: el trabajo se cierra una sola vez, y solo lo cierra el técnico asignado. */
class JobTest {
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-19T15:00:00Z");

    private Job assigned() {
        return Job.assigned(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), FIXER, OWNER, NOW);
    }

    @Test
    void newJobStartsAssignedWithoutCompletion() {
        var job = assigned();
        assertThat(job.status()).isEqualTo(JobStatus.ASSIGNED);
        assertThat(job.completedAt()).isNull();
    }

    @Test
    void completingRecordsTheMomentTheEscrowIsReleased() {
        var completed = assigned().complete(NOW);
        assertThat(completed.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(NOW);
    }

    @Test
    void completingTwiceIsRejected() {
        var completed = assigned().complete(NOW);
        assertThatThrownBy(() -> completed.complete(NOW))
                .isInstanceOf(JobConflictException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void onlyTheAssignedFixerClosesTheJob() {
        var job = assigned();
        assertThatCode(() -> job.requireExecutedBy(FIXER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> job.requireExecutedBy(OWNER))
                .isInstanceOf(JobAccessDeniedException.class);
        assertThatThrownBy(() -> job.requireExecutedBy(STRANGER))
                .isInstanceOf(JobAccessDeniedException.class);
    }
}
