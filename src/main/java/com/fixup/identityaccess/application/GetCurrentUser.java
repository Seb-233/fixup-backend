package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.domain.UserAccount;
import org.springframework.stereotype.Service;

@Service
public class GetCurrentUser {
    private final UserLookup lookup;

    public GetCurrentUser(UserLookup lookup) {
        this.lookup = lookup;
    }

    public UserAccount execute(CurrentActor actor) {
        return lookup.byId(actor.internalUserId());
    }
}
