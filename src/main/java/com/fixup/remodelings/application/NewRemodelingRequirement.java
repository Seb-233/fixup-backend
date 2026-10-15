package com.fixup.remodelings.application;

import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.api.PaymentMethod;
import java.util.List;
import java.util.UUID;

public record NewRemodelingRequirement(String title, String description, List<UUID> mediaIds,
        UrgencyLevel urgencyLevel, PaymentMethod paymentMethod) {
    public NewRemodelingRequirement {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
        java.util.Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
    }
}
