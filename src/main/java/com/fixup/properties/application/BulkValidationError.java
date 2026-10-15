package com.fixup.properties.application;

public record BulkValidationError(
    int rowNumber,
    String field,
    String code,
    String message
) {}
