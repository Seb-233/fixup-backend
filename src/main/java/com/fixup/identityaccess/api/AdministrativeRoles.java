package com.fixup.identityaccess.api;

import java.util.UUID;

/** Administrative use case; method security rechecks the caller's database privileges. */
public interface AdministrativeRoles {
    void assignRole(CurrentActor actor, UUID targetUserId, Role role);
}
