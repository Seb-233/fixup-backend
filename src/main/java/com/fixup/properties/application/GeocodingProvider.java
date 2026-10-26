package com.fixup.properties.application;

import com.fixup.properties.domain.GeocodingCandidate;
import java.util.List;
import java.math.BigDecimal;

public interface GeocodingProvider {
    List<GeocodingCandidate> geocode(String normalizedQuery);
    List<GeocodingCandidate> reverseGeocode(BigDecimal latitude, BigDecimal longitude);
}
