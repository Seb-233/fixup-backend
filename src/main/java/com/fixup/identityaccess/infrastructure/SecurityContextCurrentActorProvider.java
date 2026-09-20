package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.application.ExternalIdentityProvider;
import com.fixup.identityaccess.application.UserLookup;
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
        return new CurrentActor(user.id(), user.externalSubject(), user.roles(), user.status());
    }
}
