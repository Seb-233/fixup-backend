package com.fixup.requests.application;

import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.api.PaymentMethod;
import java.util.List;
import java.util.UUID;

public record NewRepairRequest(UUID propertyId, String title, String description,
        List<UUID> mediaIds, UrgencyLevel urgencyLevel, PaymentMethod paymentMethod) {

    public NewRepairRequest {
        PaymentMethod.requireDigitalCreation(paymentMethod);
    }

    public NewRepairRequest(UUID propertyId, String title, String description, List<UUID> mediaIds, UrgencyLevel urgencyLevel) {
        this(propertyId, title, description, mediaIds, urgencyLevel, PaymentMethod.TRANSFER);
    }

    public UrgencyLevel urgencyLevel() {
        return urgencyLevel == null ? UrgencyLevel.MEDIUM : urgencyLevel;
    }
}
