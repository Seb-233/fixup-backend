package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.RoleGranted;
import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleAssignments {
    private final UserAccounts accounts;
    private final ApplicationEventPublisher events;

    public RoleAssignments(UserAccounts accounts, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount grant(UUID userId, Role role) {
        var user = accounts.findByIdForUpdate(userId)
                .orElseThrow(() -> new IdentityProblem(IdentityProblem.Reason.USER_NOT_PROVISIONED));
        user.requireActive();
        if (user.roles().contains(role)) {
            return user;
        }
        var changed = accounts.save(user.grant(role, Instant.now()));
        // Synchronous listeners join this transaction; failures roll back role and profile.
        events.publishEvent(new RoleGranted(changed.id(), role));
        return changed;
    }
}
