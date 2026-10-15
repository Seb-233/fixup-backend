package com.fixup.requests.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Data a collaborating module may use to open an ordinary repair request. */
public record NewProjectRepairRequest(UUID propertyId, String title, String description,
        List<UUID> mediaIds, UrgencyLevel urgencyLevel, PaymentMethod paymentMethod) {

    /** Compatibility constructor while FR-UC-05 persistence is introduced in its next task. */
    public NewProjectRepairRequest(UUID propertyId, String title, String description,
            List<UUID> mediaIds, UrgencyLevel urgencyLevel) {
        this(propertyId, title, description, mediaIds, urgencyLevel, null);
    }

    public NewProjectRepairRequest {
        if (mediaIds != null && mediaIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("mediaIds cannot contain null elements");
        }
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new IllegalArgumentException("mediaIds cannot contain duplicates");
        }
    }
}
