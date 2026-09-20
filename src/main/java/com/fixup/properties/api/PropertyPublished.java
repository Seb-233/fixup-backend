package com.fixup.properties.api;

import java.time.Instant;
import java.util.UUID;

public record PropertyPublished(UUID propertyId, UUID ownerUserId, UUID managerUserId,
        PropertyType type, String title, Instant at) {
}
