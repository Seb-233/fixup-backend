package com.fixup.identityaccess.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CurrentActor(UUID internalUserId, String externalSubject, Set<Role> roles, UserStatus status) {
    public CurrentActor {
        Objects.requireNonNull(internalUserId);
        Objects.requireNonNull(externalSubject);
        Objects.requireNonNull(status);
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
