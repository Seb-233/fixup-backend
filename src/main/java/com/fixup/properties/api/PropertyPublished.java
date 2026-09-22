package com.fixup.properties.api;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-12: un inmueble quedó publicado. Lo consume notifications (FR-UC-10). */
public record PropertyPublished(UUID propertyId, UUID ownerUserId, PropertyType type,
        String title, Instant at) {
}
