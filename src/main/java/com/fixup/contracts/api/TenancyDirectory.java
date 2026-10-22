package com.fixup.contracts.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-08: el arrendatario reporta fallas del inmueble que arrienda. La única prueba de esa
 * relación es un contrato vigente, y la custodia contracts.
 */
public interface TenancyDirectory {

    /** Contrato ACTIVE o RENEWED cuya vigencia incluye la fecha dada. */
    boolean isActiveTenant(UUID tenantUserId, UUID propertyId, LocalDate on);

    /** Inmuebles con contrato vigente del arrendatario en la fecha dada. */
    List<UUID> activePropertyIds(UUID tenantUserId, LocalDate on);
}
