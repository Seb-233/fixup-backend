package com.fixup.properties.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-12: publicación en bloque. Se emite un único evento por lote y no uno por inmueble, para
 * que el propietario reciba un aviso y no cincuenta.
 */
public record PropertyBatchPublished(List<UUID> propertyIds, UUID ownerUserId,
        int publishedCount, Instant at) {

    public PropertyBatchPublished {
        propertyIds = propertyIds == null ? List.of() : List.copyOf(propertyIds);
    }
}
