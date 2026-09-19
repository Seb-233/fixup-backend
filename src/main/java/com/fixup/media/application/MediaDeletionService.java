package com.fixup.media.application;

import com.fixup.media.domain.MediaAssets;
import com.fixup.media.infrastructure.MediaDeletionJobEntity;
import com.fixup.media.infrastructure.MediaDeletionJobJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class MediaDeletionService {
    private static final Logger log = LoggerFactory.getLogger(MediaDeletionService.class);

    private final ObjectStorage objectStorage;
    private final MediaAssets mediaAssets;
    private final MediaDeletionJobJpaRepository jobRepository;
    private final ApplicationEventPublisher events;

    public MediaDeletionService(
            ObjectStorage objectStorage,
            MediaAssets mediaAssets,
            MediaDeletionJobJpaRepository jobRepository,
            ApplicationEventPublisher events) {
        this.objectStorage = objectStorage;
        this.mediaAssets = mediaAssets;
        this.jobRepository = jobRepository;
        this.events = events;
    }

    @Transactional
    public void scheduleDeletion(UUID mediaAssetId, String objectKey) {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        var job = new MediaDeletionJobEntity(jobId, mediaAssetId, objectKey, "PENDING", 0, now, now);
        jobRepository.save(job);
        events.publishEvent(new MediaDeletionRequested(jobId, mediaAssetId, objectKey));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeletionRequested(MediaDeletionRequested event) {
        executeJob(event.jobId(), event.mediaAssetId(), event.objectKey());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeJob(UUID jobId, UUID mediaAssetId, String objectKey) {
        var jobOpt = jobRepository.findById(jobId);
        Instant now = Instant.now();
        try {
            objectStorage.delete(objectKey);
            mediaAssets.findById(mediaAssetId).ifPresent(asset -> {
                mediaAssets.save(asset.markDeleted());
            });
            jobOpt.ifPresent(job -> {
                job.markCompleted(now);
                jobRepository.save(job);
            });
            log.info("Media object deleted successfully: {}", objectKey);
        } catch (Exception ex) {
            log.warn("Failed to delete media object {}: {}", objectKey, ex.getMessage());
            jobOpt.ifPresent(job -> {
                job.markFailed(now);
                jobRepository.save(job);
            });
        }
    }

    @Transactional
    public void retryPendingDeletions() {
        var pending = jobRepository.findByStatus("PENDING");
        var failed = jobRepository.findByStatus("FAILED");
        for (var job : pending) {
            executeJob(job.getId(), job.getMediaAssetId(), job.getObjectKey());
        }
        for (var job : failed) {
            executeJob(job.getId(), job.getMediaAssetId(), job.getObjectKey());
        }
    }
}
