package com.fixup.properties.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PropertySnapshot(
    UUID id,
    UUID ownerUserId,
    String name,
    String address,
    String city,
    BigDecimal areaM2,
    BigDecimal latitude,
    BigDecimal longitude,
    UUID managerUserId,
    String neighborhood,
    PropertyStatus status
) {
    /** FR-UC-11: quien decide sobre el inmueble es su propietario o el administrador que lo gestiona. */
    public boolean isManagedBy(UUID userId) {
        return userId != null && userId.equals(managerUserId);
    }
}
