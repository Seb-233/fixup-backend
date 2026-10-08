package com.fixup.properties.application;

import java.util.List;
import java.util.UUID;

public record BulkImportResult(
    int imported,
    int failed,
    List<UUID> importedIds,
    List<BulkValidationError> errors
) {}
