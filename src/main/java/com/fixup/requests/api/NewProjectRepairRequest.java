package com.fixup.requests.api;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Data a collaborating module may use to open an ordinary repair request. */
public record NewProjectRepairRequest(UUID propertyId, String title, String description,
        List<UUID> mediaIds, UrgencyLevel urgencyLevel) {

    public NewProjectRepairRequest {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
        if (mediaIds.contains(null)) {
            throw new IllegalArgumentException("mediaIds cannot contain null elements");
        }
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new IllegalArgumentException("mediaIds cannot contain duplicates");
        }
    }
}
