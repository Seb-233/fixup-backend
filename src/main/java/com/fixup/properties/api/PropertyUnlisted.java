package com.fixup.properties.api;

import java.time.Instant;
import java.util.UUID;

public record PropertyUnlisted(UUID propertyId, UUID ownerUserId, UUID managerUserId,
        String title, Instant at) {
}
