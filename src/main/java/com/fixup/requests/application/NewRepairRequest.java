package com.fixup.requests.application;

import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.api.PaymentMethod;
import java.util.List;
import java.util.UUID;

public record NewRepairRequest(UUID propertyId, String title, String description,
        List<UUID> mediaIds, UrgencyLevel urgencyLevel, PaymentMethod paymentMethod) {

    public NewRepairRequest {
        java.util.Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
    }

    public UrgencyLevel urgencyLevel() {
        return urgencyLevel == null ? UrgencyLevel.MEDIUM : urgencyLevel;
    }
}
