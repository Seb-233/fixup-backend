package com.fixup.properties.application;

import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
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
    PropertyType type,
    PropertyStatus status,
    String title,
    String description,
    String zone,
    BigDecimal monthlyRentSuggestion,
    Instant publishedAt,
    Instant unlistedAt
) {
    public static PropertySummary from(Property property) {
        return new PropertySummary(
            property.id(),
            property.name(),
            property.address(),
            property.city(),
            property.areaM2(),
            property.type(),
            property.status(),
            property.title(),
            property.description(),
            property.zone(),
            property.monthlyRentSuggestion(),
            property.publishedAt(),
            property.unlistedAt()
        );
    }
}
