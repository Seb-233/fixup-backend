package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.domain.RolePolicy;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelectInitialRole {
    private final RoleAssignments assignments;
    private final UserLookup lookup;

    public SelectInitialRole(RoleAssignments assignments, UserLookup lookup) {
        this.assignments = assignments;
        this.lookup = lookup;
    }

    @Transactional
    public Set<Role> execute(CurrentActor actor, Role role) {
        var user = lookup.byId(actor.internalUserId());
        if (user.roles().contains(role)) {
            return user.roles();
        }
        RolePolicy.requireSelfAssignable(role);
        return assignments.grant(actor.internalUserId(), role).roles();
    }
}
