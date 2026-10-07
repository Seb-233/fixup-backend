package com.fixup.properties.application;

public record BulkPropertyRow(
    int rowNumber,
    String name,
    String address,
    String city,
    String areaM2Str
) {}
