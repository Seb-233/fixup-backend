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

    public SelectInitialRole(RoleAssignments assignments) {
        this.assignments = assignments;
    }

    @Transactional
    public Set<Role> execute(CurrentActor actor, Role role) {
        RolePolicy.requireSelfAssignable(role);
        return assignments.grant(actor.internalUserId(), role).roles();
    }
}
