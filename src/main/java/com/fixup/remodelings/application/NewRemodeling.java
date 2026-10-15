package com.fixup.remodelings.application;

import java.util.List;
import java.util.UUID;

public record NewRemodeling(UUID propertyId, String title, String description,
        List<NewRemodelingRequirement> requirements) {
    public NewRemodeling {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
