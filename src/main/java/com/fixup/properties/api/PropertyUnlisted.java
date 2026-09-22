package com.fixup.properties.api;

import java.time.Instant;
import java.util.UUID;

/** FR-UC-12: un inmueble publicado se retiró de la oferta. */
public record PropertyUnlisted(UUID propertyId, UUID ownerUserId, String title, Instant at) {
}
