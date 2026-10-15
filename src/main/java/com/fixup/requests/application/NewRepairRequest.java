package com.fixup.requests.application;

import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.api.PaymentMethod;
import java.util.List;
import java.util.UUID;

public record NewRepairRequest(UUID propertyId, String title, String description,
        List<UUID> mediaIds, UrgencyLevel urgencyLevel, PaymentMethod paymentMethod) {

    /**
     * Compatibility constructor for the pre-FR-UC-05 HTTP and persistence flow.
     * The next task makes the method mandatory when the aggregate is persisted.
     */
    public NewRepairRequest(UUID propertyId, String title, String description,
            List<UUID> mediaIds, UrgencyLevel urgencyLevel) {
        this(propertyId, title, description, mediaIds, urgencyLevel, null);
    }

    public UrgencyLevel urgencyLevel() {
        return urgencyLevel == null ? UrgencyLevel.MEDIUM : urgencyLevel;
    }
}
