package com.fixup.properties.api;

import java.util.UUID;

public interface PropertyDirectory {
    PropertySnapshot requireOwnedBy(UUID propertyId, UUID ownerUserId);
}
