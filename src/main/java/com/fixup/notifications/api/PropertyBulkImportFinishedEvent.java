package com.fixup.notifications.api;

import java.time.Instant;
import java.util.UUID;

/** Stub: replace with import from properties module when ready. */
public record PropertyBulkImportFinishedEvent(
        UUID importId,
        UUID userId,
        int totalProperties,
        int importedCount,
        Instant finishedAt) {
}
