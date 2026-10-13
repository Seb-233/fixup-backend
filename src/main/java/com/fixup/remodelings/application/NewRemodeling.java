package com.fixup.remodelings.application;

import com.fixup.remodelings.domain.RemodelingPlan;
import java.util.List;
import java.util.UUID;

public record NewRemodeling(UUID propertyId, String title, String description,
        List<NewRemodelingRequirement> requirements, RemodelingPlan plan) {
    public NewRemodeling {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        plan = plan == null ? RemodelingPlan.IMPROVEMENT : plan;
    }

    public NewRemodeling(UUID propertyId, String title, String description,
            List<NewRemodelingRequirement> requirements) {
        this(propertyId, title, description, requirements, RemodelingPlan.IMPROVEMENT);
    }
}
