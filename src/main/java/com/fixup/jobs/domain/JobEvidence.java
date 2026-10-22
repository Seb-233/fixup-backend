package com.fixup.jobs.domain;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-19: una foto del trabajo antes, durante o después de ejecutarlo. */
public record JobEvidence(UUID id, UUID jobId, UUID mediaId, Stage stage, String caption, Instant createdAt) {
    public enum Stage { BEFORE, DURING, AFTER }
}
