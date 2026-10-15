package com.fixup.properties.application;

import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertySnapshot;
import com.fixup.properties.domain.Properties;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class PropertyDirectoryImpl implements PropertyDirectory {

    private final Properties properties;

    PropertyDirectoryImpl(Properties properties) {
        this.properties = properties;
    }

    @Override
    public PropertySnapshot requireOwnedBy(UUID propertyId, UUID ownerUserId) {
        var property = properties.findById(propertyId)
            .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        if (!property.ownerUserId().equals(ownerUserId)) {
            throw new PropertyNotFoundException(propertyId);
        }

        return new PropertySnapshot(
            property.id(),
            property.ownerUserId(),
            property.name(),
            property.address(),
            property.city(),
            property.areaM2()
        );
    }
}
