package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.AdministrativeRoles;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.domain.RolePolicy;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignAdministrativeRole implements AdministrativeRoles {
    private final RoleAssignments assignments;

    public AssignAdministrativeRole(RoleAssignments assignments) {
        this.assignments = assignments;
    }

    @Override
    @PreAuthorize("@internalAuthorization.isCurrentAdmin(#actor)")
    @Transactional
    public void assignRole(CurrentActor actor, UUID targetUserId, Role role) {
        RolePolicy.requireAdministrator(actor);
        assignments.grant(targetUserId, role);
    }
}
