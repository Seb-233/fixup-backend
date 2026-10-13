package com.fixup.remodelings.domain;

import java.util.Objects;
import java.util.UUID;

public record RemodelingRequirement(UUID id, UUID projectId, UUID requestId, int position) {
    public RemodelingRequirement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
        }
    }
}
