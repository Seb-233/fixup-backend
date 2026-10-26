package com.fixup.jobs.application;

import com.fixup.fixers.api.FixerEligibility;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.api.JobConflictException;
import com.fixup.jobs.api.JobNotFoundException;
import com.fixup.jobs.domain.JobEvidence;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import com.fixup.media.api.MediaAttachmentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-19: el técnico adjunta fotos del antes, el durante y el después. Las fotos pasan por el
 * mismo flujo de medios que el resto de la plataforma: URL firmada, confirmación y adjunto.
 */
@Service
public class AddJobEvidence {
    static final int MAX_EVIDENCE_PER_JOB = 24;

    private final Jobs jobs;
    private final JobRecords records;
    private final FixerEligibility eligibility;
    private final MediaAttachmentService media;

    AddJobEvidence(Jobs jobs, JobRecords records, FixerEligibility eligibility, MediaAttachmentService media) {
        this.jobs = jobs;
        this.records = records;
        this.eligibility = eligibility;
        this.media = media;
    }

    @Transactional
    public List<JobEvidence> execute(CurrentActor actor, UUID jobId, List<UUID> mediaIds, JobEvidence.Stage stage,
            String caption) {
        JobAccess.requireActiveFixer(actor);
        eligibility.requireVerified(actor);
        var job = jobs.findByIdForUpdate(jobId).orElseThrow(JobNotFoundException::new);
        job.requireExecutedBy(actor.internalUserId());
        if (stage == null) {
            throw new JobConflictException("INVALID_STAGE", "The evidence stage is required");
        }
        if (mediaIds == null || mediaIds.isEmpty()) {
            return List.of();
        }
        if (records.evidenceOf(jobId).size() + mediaIds.size() > MAX_EVIDENCE_PER_JOB) {
            throw new JobConflictException("TOO_MANY_EVIDENCES",
                    "A job keeps at most " + MAX_EVIDENCE_PER_JOB + " evidence photos");
        }
        var trimmed = caption == null || caption.isBlank() ? null : caption.trim();
        if (trimmed != null && trimmed.length() > 200) {
            throw new JobConflictException("CAPTION_TOO_LONG", "A caption has at most 200 characters");
        }
        media.attachJobEvidence(actor.internalUserId(), mediaIds);
        var now = Instant.now();
        for (UUID mediaId : mediaIds) {
            records.addEvidence(new JobEvidence(UUID.randomUUID(), jobId, mediaId, stage, trimmed, now));
        }
        return records.evidenceOf(jobId);
    }
}
