package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.api.PropertyStatus;
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
        if ((actor.hasRole(Role.TENANT) || actor.hasRole(Role.PLATFORM_ADMIN)
                || actor.hasRole(Role.REAL_ESTATE_MANAGER))
                && ownershipScope == OwnershipScope.ALL) {
            return;
        }
        throw new PropertyAccessDeniedException("Actor cannot search properties with the requested ownership scope");
    }

    /** FR-UC-06: el catálogo es para quien busca o administra vivienda, no para los técnicos. */
    static void requireCanBrowseCatalog(CurrentActor actor) {
        requireActive(actor);
        if (actor.hasRole(Role.OWNER) || actor.hasRole(Role.TENANT)
                || actor.hasRole(Role.REAL_ESTATE_MANAGER) || actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        throw new PropertyAccessDeniedException("Actor cannot browse the property catalog");
    }

    static void requireCanGeocode(CurrentActor actor) {
        requireActive(actor);
        if (actor.hasRole(Role.OWNER)
                || actor.hasRole(Role.TENANT)
                || actor.hasRole(Role.REAL_ESTATE_MANAGER)
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
        if (actor.hasRole(Role.REAL_ESTATE_MANAGER) && property.isControlledBy(actor.internalUserId())) {
            return;
        }
        throw new PropertyNotFoundException(property.id());
    }

    /**
     * FR-UC-02 y FR-UC-11: editar, publicar y retirar el aviso lo hacen su propietario o el
     * administrador inmobiliario que lo gestiona. Un inmueble ajeno responde 404, no 403: no se
     * confirma su existencia a quien no tiene relación con él.
     */
    static void requireCanManage(CurrentActor actor, Property property) {
        requireActive(actor);
        if (actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        if ((actor.hasRole(Role.OWNER) || actor.hasRole(Role.REAL_ESTATE_MANAGER))
                && property.isControlledBy(actor.internalUserId())) {
            return;
        }
        throw new PropertyNotFoundException(property.id());
    }

    /** El aviso público lo ve cualquiera que pueda navegar el catálogo, mientras esté publicado. */
    static void requireCanReadListing(CurrentActor actor, Property property) {
        requireCanBrowseCatalog(actor);
        if (property.status() == PropertyStatus.PUBLISHED || property.isControlledBy(actor.internalUserId())
                || actor.hasRole(Role.PLATFORM_ADMIN)) {
            return;
        }
        throw new PropertyNotFoundException(property.id());
    }
}
