package com.fixup.properties.api;

import java.util.UUID;

public record PropertyPublished(
    UUID propertyId,
    UUID ownerUserId,
    String name
) {}
