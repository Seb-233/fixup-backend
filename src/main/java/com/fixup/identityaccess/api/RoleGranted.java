package com.fixup.identityaccess.api;

import java.util.UUID;

/** Internal event, published in the role assignment transaction. Never contains a JWT. */
public record RoleGranted(UUID internalUserId, Role role) {
}
