package com.fixup.properties.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Bounding box used to reduce geographic search candidates before the exact distance calculation. */
public record GeographicBoundingBox(
        BigDecimal minLatitude,
        BigDecimal maxLatitude,
        BigDecimal minLongitude,
        BigDecimal maxLongitude,
        boolean allLongitudes,
        boolean crossesAntimeridian) {

    public GeographicBoundingBox {
        Objects.requireNonNull(minLatitude, "minLatitude must not be null");
        Objects.requireNonNull(maxLatitude, "maxLatitude must not be null");
        Objects.requireNonNull(minLongitude, "minLongitude must not be null");
        Objects.requireNonNull(maxLongitude, "maxLongitude must not be null");
    }
}
