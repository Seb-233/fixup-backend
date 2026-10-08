package com.fixup.properties.application;

import java.util.List;

public record BulkValidationResult(
    int total,
    int validCount,
    List<BulkValidationError> errors
) {}
