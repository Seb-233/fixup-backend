package com.fixup.properties.api;

import java.util.UUID;

public class PropertyNotFoundException extends RuntimeException {
    private final UUID propertyId;

    public PropertyNotFoundException(UUID propertyId) {
        super("Property " + propertyId + " not found");
        this.propertyId = propertyId;
    }

    public UUID getPropertyId() {
        return propertyId;
    }
}
