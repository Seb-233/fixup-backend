package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.domain.OwnershipScope;
import com.fixup.properties.domain.Property;

final class PropertyAccess {

    private PropertyAccess() {}

    static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new PropertyAccessDeniedException("Actor is not active");
        }
    }

    static void requireCanSearch(CurrentActor actor, OwnershipScope ownershipScope) {
        requireActive(actor);
        if (actor.hasRole(Role.OWNER)) {
            return;
        }
        if ((actor.hasRole(Role.TENANT) || actor.hasRole(Role.PLATFORM_ADMIN))
                && ownershipScope == OwnershipScope.ALL) {
            return;
        }
        throw new PropertyAccessDeniedException("Actor cannot search properties with the requested ownership scope");
    }

    static void requireCanGeocode(CurrentActor actor) {
        requireActive(actor);
        if (actor.hasRole(Role.OWNER)
                || actor.hasRole(Role.TENANT)
                || actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        throw new PropertyAccessDeniedException("Actor cannot geocode addresses");
    }

    static void requireActiveOwner(CurrentActor actor) {
        requireActive(actor);
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
