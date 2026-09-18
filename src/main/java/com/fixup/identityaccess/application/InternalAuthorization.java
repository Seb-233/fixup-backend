package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.domain.IdentityProblem;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component("internalAuthorization")
public class InternalAuthorization {
    private final CurrentActorProvider actors;

    public InternalAuthorization(CurrentActorProvider actors) {
        this.actors = actors;
    }

    public boolean isCurrentAdmin(CurrentActor actor) {
        try {
            var current = actors.currentActor();
            return current.internalUserId().equals(actor.internalUserId())
                    && current.hasRole(Role.PLATFORM_ADMIN);
        } catch (IdentityProblem | AuthenticationException denied) {
            return false;
        }
    }
}
