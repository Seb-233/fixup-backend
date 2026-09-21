package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.application.ExternalIdentityProvider;
import com.fixup.identityaccess.application.UserLookup;
import com.fixup.shared.security.DatabaseActorContext;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class SecurityContextCurrentActorProvider implements CurrentActorProvider {
    private final ExternalIdentityProvider identity;
    private final UserLookup lookup;

    SecurityContextCurrentActorProvider(ExternalIdentityProvider identity, UserLookup lookup) {
        this.identity = identity;
        this.lookup = lookup;
    }

    @Override
    public CurrentActor currentActor() {
        var user = lookup.bySubject(identity.currentSubject());
        var actor = new CurrentActor(user.id(), user.externalSubject(), user.roles(), user.status());
        // FR-UC-25: publish it so the transaction manager can activate Row-Level Security for the
        // transactions this request opens. Roles come straight from the database, never the token.
        DatabaseActorContext.set(new DatabaseActorContext.Actor(actor.internalUserId(),
                actor.roles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet())));
        return actor;
    }
}
