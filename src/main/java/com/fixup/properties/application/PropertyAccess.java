package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.domain.Property;

final class PropertyAccess {

    private PropertyAccess() {}

    static void requireActiveOwner(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new PropertyAccessDeniedException("Actor is not active");
        }
        if (!actor.hasRole(Role.OWNER)) {
            throw new PropertyAccessDeniedException("Actor does not have OWNER role");
        }
    }

    static void requireCanRead(CurrentActor actor, Property property) {
        if (actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        if (actor.hasRole(Role.OWNER) && property.ownerUserId().equals(actor.internalUserId())) {
            return;
        }
        throw new PropertyNotFoundException(property.id());
    }

    /**
     * FR-UC-12: publicar y retirar son actos de disposición sobre el inmueble, no lecturas, y solo
     * su dueño los ejerce. A diferencia de {@link #requireCanRead}, que responde 404 para no
     * revelar la existencia de un inmueble ajeno, aquí se responde 403: quien llega hasta esta ruta
     * ya afirmó conocer el identificador y pretender disponer de él, y el registro de auditoría
     * distingue mejor un intento de publicar lo ajeno que un identificador inexistente.
     * PLATFORM_ADMIN tampoco publica: ningún caso de uso le permite ofertar a nombre de otro.
     */
    static void requireCanPublish(CurrentActor actor, Property property) {
        if (!property.ownerUserId().equals(actor.internalUserId())) {
            throw new PropertyAccessDeniedException("Actor does not own this property");
        }
    }
}
