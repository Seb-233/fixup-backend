package com.fixup.identityaccess.domain;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public record UserAccount(UUID id, String externalSubject, String email, String displayName,
        UserStatus status, Set<Role> roles, Instant createdAt, Instant updatedAt) {
    public UserAccount {
        roles = Set.copyOf(roles);
    }

    public void requireActive() {
        if (status != UserStatus.ACTIVE) {
            throw new IdentityProblem(IdentityProblem.Reason.ACCESS_DENIED);
        }
    }

    public UserAccount grant(Role role, Instant now) {
        requireActive();
        var granted = new HashSet<>(roles);
        if (!granted.add(role)) {
            return this;
        }
        return new UserAccount(id, externalSubject, email, displayName, status, granted, createdAt, now);
    }
}
