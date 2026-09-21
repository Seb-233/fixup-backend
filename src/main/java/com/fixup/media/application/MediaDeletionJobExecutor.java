package com.fixup.media.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes media object deletion against ObjectStorage outside database transactions.
 */
@Component
public class MediaDeletionJobExecutor {
    private static final Logger log = LoggerFactory.getLogger(MediaDeletionJobExecutor.class);

    private final ObjectStorage objectStorage;
    private final MediaDeletionJobFinalizer finalizer;

    public MediaDeletionJobExecutor(ObjectStorage objectStorage, MediaDeletionJobFinalizer finalizer) {
        this.objectStorage = objectStorage;
        this.finalizer = finalizer;
    }

    public void execute(ClaimedDeletionJob job) {
        try {
            objectStorage.delete(job.objectKey());
            finalizer.completeJob(job);
        } catch (Exception ex) {
            log.warn("Failed to delete media object for asset {}: {}",
                    job.mediaAssetId(), MediaDeletionJobFinalizer.sanitizeError(ex));
            finalizer.failJob(job, ex);
        }
    }
}
