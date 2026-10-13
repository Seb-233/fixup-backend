package com.fixup.remodelings.application;

import com.fixup.requests.api.UrgencyLevel;
import java.util.List;
import java.util.UUID;

public record NewRemodelingRequirement(String title, String description, List<UUID> mediaIds,
        UrgencyLevel urgencyLevel) {
    public NewRemodelingRequirement {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
    }
}
