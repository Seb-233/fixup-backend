package com.fixup.requests.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative identities and completion time, supplied by the coordinating use case. */
public record RepairRequestCompletionCommand(UUID requestId, UUID ownerUserId,
        UUID fixerUserId, Instant completedAt, UUID changedByUserId, String changedBySubject) {
    public RepairRequestCompletionCommand(UUID requestId, UUID ownerUserId,
            UUID fixerUserId, Instant completedAt) {
        this(requestId, ownerUserId, fixerUserId, completedAt, fixerUserId, "SYSTEM");
    }

    public RepairRequestCompletionCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(fixerUserId, "fixerUserId must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(changedBySubject, "changedBySubject must not be null");
    }
}
