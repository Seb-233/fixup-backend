package com.fixup.jobs.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The assigned fixer completed the job, financial settlement and request closure in the caller's
 * transaction. This is not a commit confirmation: synchronous consumers (such as visits) share
 * that transaction; external effects must wait for its successful commit.
 */
public record JobCompleted(UUID jobId, UUID quotationId, UUID requestId, UUID ownerUserId,
        UUID fixerUserId, String paymentMethod, long agreedAmount, Long receivedAmount,
        Instant completedAt) {
}
