package com.fixup.properties.domain;

import java.util.Objects;

/** A persisted property accompanied by the distance calculated by the database. */
public record PropertySearchMatch(Property property, double distanceKm) {
    public PropertySearchMatch {
        Objects.requireNonNull(property, "property must not be null");
        if (!Double.isFinite(distanceKm) || distanceKm < 0) {
            throw new IllegalArgumentException("distanceKm must be finite and non-negative");
        }
    }
}
