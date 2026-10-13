package com.fixup.properties.application;

import com.fixup.properties.domain.GeocodingCandidate;
import java.util.List;

public interface GeocodingProvider {
    List<GeocodingCandidate> geocode(String normalizedQuery);
}
