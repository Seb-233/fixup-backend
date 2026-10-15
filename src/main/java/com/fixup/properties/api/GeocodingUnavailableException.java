package com.fixup.properties.api;

public class GeocodingUnavailableException extends RuntimeException {
    public GeocodingUnavailableException(String message) {
        super(message);
    }

    public GeocodingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
