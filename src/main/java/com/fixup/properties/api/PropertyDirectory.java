package com.fixup.properties.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PropertyDirectory {
    PropertySnapshot requireOwnedBy(UUID propertyId, UUID ownerUserId);

    /**
     * FR-UC-08 y FR-UC-11: el inmueble tal como lo ve quien lo pide, sin exigir que sea su dueño. La
     * relación del llamador con el inmueble (arrendatario, administrador) la decide el módulo que
     * llama; aquí solo se garantiza que exista.
     *
     * @throws PropertyNotFoundException si no existe o la fila no es visible para el actor actual.
     */
    PropertySnapshot require(UUID propertyId);

    /** Nombres de los inmuebles pedidos; los que no existen o no son visibles no aparecen. */
    Map<UUID, String> names(Collection<UUID> propertyIds);

    /** Los inmuebles pedidos que existen y son visibles para el actor actual. */
    List<PropertySnapshot> findAll(Collection<UUID> propertyIds);
}
