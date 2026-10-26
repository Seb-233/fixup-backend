package com.fixup.remodelings.application;

import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.api.PaymentMethod;
import java.util.List;
import java.util.UUID;

public record NewRemodelingRequirement(String title, String description, List<UUID> mediaIds,
        UrgencyLevel urgencyLevel, PaymentMethod paymentMethod) {
    public NewRemodelingRequirement {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
        PaymentMethod.requireDigitalCreation(paymentMethod);
    }
    public NewRemodelingRequirement(String title, String description, List<UUID> mediaIds, UrgencyLevel urgencyLevel) {
        this(title, description, mediaIds, urgencyLevel, PaymentMethod.TRANSFER);
    }
}
