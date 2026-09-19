package com.fixup.media.application;

import com.fixup.media.domain.MediaDeletionJobType;
import com.fixup.media.infrastructure.MediaDeletionJobEntity;
import com.fixup.media.infrastructure.MediaDeletionJobJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers durable media deletion jobs and publishes events.
 * Does not directly invoke ObjectStorage or execute deletions.
 */
@Service
public class MediaDeletionService {
    private final MediaDeletionJobJpaRepository jobRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int maxAttempts;

    public MediaDeletionService(
            MediaDeletionJobJpaRepository jobRepository,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${fixup.media.deletion.max-attempts:5}") int maxAttempts) {
        this.jobRepository = jobRepository;
        this.events = events;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public void schedulePieceDeletion(UUID mediaAssetId, String objectKey) {
        registerJob(mediaAssetId, objectKey, MediaDeletionJobType.PIECE_DELETION);
    }

    @Transactional
    public void scheduleInvalidObjectPurge(UUID mediaAssetId, String objectKey) {
        registerJob(mediaAssetId, objectKey, MediaDeletionJobType.INVALID_PURGE);
    }

    @Transactional
    public void scheduleDeletion(UUID mediaAssetId, String objectKey) {
        schedulePieceDeletion(mediaAssetId, objectKey);
    }

    private void registerJob(UUID mediaAssetId, String objectKey, MediaDeletionJobType jobType) {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        var job = MediaDeletionJobEntity.pending(jobId, mediaAssetId, objectKey, jobType, maxAttempts, now);
        jobRepository.save(job);
        events.publishEvent(new MediaDeletionRequested(jobId, mediaAssetId, objectKey));
    }
}
