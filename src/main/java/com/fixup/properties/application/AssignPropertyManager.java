package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.domain.Properties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-11: el propietario delega la gestión del inmueble en un administrador inmobiliario. Desde
 * ese momento el administrador ve y decide las solicitudes de mantenimiento del inmueble. Solo el
 * propietario delega o revoca: el administrador no puede transferirse la gestión a otro.
 */
@Service
public class AssignPropertyManager {
    private final Properties properties;
    private final CurrentActorProvider actors;
    private final UserDirectory users;

    AssignPropertyManager(Properties properties, CurrentActorProvider actors, UserDirectory users) {
        this.properties = properties;
        this.actors = actors;
        this.users = users;
    }

    @Transactional
    public PropertySummary execute(UUID propertyId, UUID managerUserId) {
        var actor = actors.currentActor();
        PropertyAccess.requireActive(actor);
        var property = properties.findById(propertyId).orElseThrow(() -> new PropertyNotFoundException(propertyId));
        if (!property.ownerUserId().equals(actor.internalUserId()) && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new PropertyNotFoundException(propertyId);
        }
        if (managerUserId != null && !users.hasActiveRole(managerUserId, Role.REAL_ESTATE_MANAGER)) {
            throw new PropertyAccessDeniedException("The chosen user is not an active real estate manager");
        }
        property.assignManager(managerUserId, Instant.now());
        return PropertySummary.from(properties.save(property));
    }

    @Transactional(readOnly = true)
    public List<UserDirectory.UserCard> availableManagers() {
        PropertyAccess.requireActiveOwner(actors.currentActor());
        return users.activeUsersWithRole(Role.REAL_ESTATE_MANAGER);
    }
}
