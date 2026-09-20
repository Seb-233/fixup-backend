package com.fixup.media.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Coordinates periodic recovery and claiming of media deletion jobs,
 * and processes deletions immediately after transaction commit.
 */
@Component
public class MediaDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(MediaDeletionWorker.class);

    private final MediaDeletionJobClaimer claimer;
    private final MediaDeletionJobExecutor executor;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final Duration lockTimeout;

    public MediaDeletionWorker(
            MediaDeletionJobClaimer claimer,
            MediaDeletionJobExecutor executor,
            Clock clock,
            @Value("${fixup.media.deletion.enabled:true}") boolean enabled,
            @Value("${fixup.media.deletion.batch-size:10}") int batchSize,
            @Value("${fixup.media.deletion.lock-timeout:5m}") Duration lockTimeout) {
        this.claimer = claimer;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.lockTimeout = lockTimeout;
    }

    @Scheduled(fixedDelayString = "${fixup.media.deletion.polling-delay:30s}")
    public void runPollingCycle() {
        if (!enabled) {
            return;
        }
        processCycle();
    }

    public int triggerManualCycle() {
        return processCycle();
    }

    private int processCycle() {
        Instant now = Instant.now(clock);
        int recovered = claimer.recoverStaleJobs(now, lockTimeout);
        if (recovered > 0) {
            log.info("Recovered {} stale media deletion jobs back to PENDING", recovered);
        }

        List<ClaimedDeletionJob> batch = claimer.claimNextBatch(now, batchSize);
        for (ClaimedDeletionJob job : batch) {
            executor.execute(job);
        }
        return recovered + batch.size();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeletionRequested(MediaDeletionRequested event) {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now(clock);
        claimer.claimSpecificJob(event.jobId(), now).ifPresent(executor::execute);
    }
}
