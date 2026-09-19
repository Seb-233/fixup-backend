package com.fixup.media.application;

import com.fixup.media.domain.MediaDeletionJobType;
import java.util.UUID;

public record ClaimedDeletionJob(
        UUID jobId,
        UUID claimToken,
        MediaDeletionJobType jobType,
        UUID mediaAssetId,
        String objectKey,
        int currentAttempts,
        int maxAttempts
) {}
