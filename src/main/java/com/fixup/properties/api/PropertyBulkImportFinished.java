package com.fixup.properties.api;

import java.util.UUID;

public record PropertyBulkImportFinished(
    UUID batchId,
    UUID publisherUserId,
    int importedCount,
    int failedCount
) {}
