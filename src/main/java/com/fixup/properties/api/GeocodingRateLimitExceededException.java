package com.fixup.properties.api;

public class GeocodingRateLimitExceededException extends RuntimeException {
    public GeocodingRateLimitExceededException() {
        super("Geocoding rate limit exceeded");
    }
}
