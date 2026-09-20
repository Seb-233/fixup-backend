package com.fixup.properties.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PropertySnapshot(
        UUID id,
        UUID ownerUserId,
        UUID managerUserId,
        PropertyType type,
        PropertyStatus status,
        String title,
        String city,
        String zone,
        BigDecimal monthlyRentSuggestion,
        Integer bedrooms,
        Integer bathrooms,
        Double surfaceM2,
        List<UUID> mediaIds,
        Instant createdAt,
        Instant publishedAt) {

    public boolean isPublished() {
        return status == PropertyStatus.PUBLISHED;
    }

    public void requireOwnedBy(UUID userId) {
        if (!ownerUserId.equals(userId) && (managerUserId == null || !managerUserId.equals(userId))) {
            throw new PropertyAccessDeniedException();
        }
    }
}
