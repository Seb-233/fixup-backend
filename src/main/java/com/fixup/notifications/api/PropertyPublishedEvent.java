package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Stub: replace with import from properties module when ready. */
public record PropertyPublishedEvent(
        UUID propertyId,
        UUID ownerUserId,
        Instant publishedAt) {
}
