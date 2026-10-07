package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.domain.Property;

final class PropertyAccess {

    private PropertyAccess() {}

    static void requireActiveOwner(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new PropertyAccessDeniedException("Actor is not active");
        }
        if (!actor.hasRole(Role.OWNER)
                && !actor.hasRole(Role.REAL_ESTATE_MANAGER)
                && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new PropertyAccessDeniedException("Actor does not have OWNER, REAL_ESTATE_MANAGER or PLATFORM_ADMIN role");
        }
    }

    static void requireCanRead(CurrentActor actor, Property property) {
        if (actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        if (actor.hasRole(Role.OWNER) && property.ownerUserId().equals(actor.internalUserId())) {
            return;
        }
        throw new com.fixup.properties.api.PropertyNotFoundException(property.id());
    }
}
