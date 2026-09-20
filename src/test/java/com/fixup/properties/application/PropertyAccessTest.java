package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyAccessDeniedException;
import com.fixup.properties.domain.Property;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyAccessTest {

    @Test
    void requiresActiveOwnerForWriteAccess() {
        var activeOwner = new CurrentActor(UUID.randomUUID(), "auth0|1", Set.of(Role.OWNER), UserStatus.ACTIVE);
        assertDoesNotThrow(() -> PropertyAccess.requireActiveOwner(activeOwner));

        var suspendedOwner = new CurrentActor(UUID.randomUUID(), "auth0|2", Set.of(Role.OWNER), UserStatus.SUSPENDED);
        assertThrows(PropertyAccessDeniedException.class, () -> PropertyAccess.requireActiveOwner(suspendedOwner));

        var activeTenant = new CurrentActor(UUID.randomUUID(), "auth0|3", Set.of(Role.TENANT), UserStatus.ACTIVE);
        assertThrows(PropertyAccessDeniedException.class, () -> PropertyAccess.requireActiveOwner(activeTenant));
    }

    @Test
    void grantsReadAccessToOwnerAndPlatformAdmin() {
        var ownerId = UUID.randomUUID();
        var property = Property.create(ownerId, "Name", "Addr", "City", new BigDecimal("10"));

        var owner = new CurrentActor(ownerId, "auth0|1", Set.of(Role.OWNER), UserStatus.ACTIVE);
        assertDoesNotThrow(() -> PropertyAccess.requireCanRead(owner, property));

        var admin = new CurrentActor(UUID.randomUUID(), "auth0|2", Set.of(Role.PLATFORM_ADMIN), UserStatus.ACTIVE);
        assertDoesNotThrow(() -> PropertyAccess.requireCanRead(admin, property));

        var otherOwner = new CurrentActor(UUID.randomUUID(), "auth0|3", Set.of(Role.OWNER), UserStatus.ACTIVE);
        assertThrows(PropertyAccessDeniedException.class, () -> PropertyAccess.requireCanRead(otherOwner, property));
    }
}
