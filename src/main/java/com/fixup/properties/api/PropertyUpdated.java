package com.fixup.properties.api;

import java.time.Instant;
import java.util.UUID;

public record PropertyUpdated(UUID propertyId, UUID ownerUserId, UUID managerUserId,
        Instant at) {
}
