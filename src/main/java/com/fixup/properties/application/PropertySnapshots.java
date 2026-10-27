package com.fixup.properties.application;

import com.fixup.properties.api.PropertySnapshot;
import com.fixup.properties.domain.Property;

final class PropertySnapshots {
    private PropertySnapshots() {
    }

    static PropertySnapshot of(Property property) {
        return new PropertySnapshot(property.id(), property.ownerUserId(), property.name(), property.address(),
                property.city(), property.areaM2(), property.latitude(), property.longitude(),
                property.managerUserId(), property.listing().neighborhood(), property.status());
    }
}
