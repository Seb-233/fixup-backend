package com.fixup.properties.application;

import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.domain.Property;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PropertySummary(
    UUID id,
    String name,
    String address,
    String city,
    BigDecimal areaM2,
    PropertyStatus status,
    Instant publishedAt
) {
    public static PropertySummary from(Property property) {
        return new PropertySummary(
            property.id(),
            property.name(),
            property.address(),
            property.city(),
            property.areaM2(),
            property.status(),
            property.publishedAt()
        );
    }
}
