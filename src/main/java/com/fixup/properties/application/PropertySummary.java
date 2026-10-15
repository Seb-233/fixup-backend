package com.fixup.properties.application;

import com.fixup.properties.domain.Property;

import java.math.BigDecimal;
import java.util.UUID;

public record PropertySummary(
    UUID id,
    String name,
    String address,
    String city,
    BigDecimal areaM2
) {
    public static PropertySummary from(Property property) {
        return new PropertySummary(
            property.id(),
            property.name(),
            property.address(),
            property.city(),
            property.areaM2()
        );
    }
}
