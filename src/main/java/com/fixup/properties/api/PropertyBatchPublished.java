package com.fixup.properties.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PropertyBatchPublished(List<UUID> propertyIds, UUID ownerUserId,
        UUID managerUserId, int publishedCount, Instant at) {
}
