package com.fixup.identityaccess.application;

import com.fixup.identityaccess.domain.IdentityProblem;
import org.springframework.stereotype.Service;

@Service
public class BootstrapUser {
    private final ExternalIdentityProvider identityProvider;
    private final UserRegistration registration;
    private final UserLookup lookup;

    public BootstrapUser(ExternalIdentityProvider identityProvider, UserRegistration registration, UserLookup lookup) {
        this.identityProvider = identityProvider;
        this.registration = registration;
        this.lookup = lookup;
    }

    public BootstrapResult execute() {
        var identity = identityProvider.currentIdentity();
        try {
            return registration.register(identity);
        } catch (IdentityProblem conflict) {
            if (conflict.reason() != IdentityProblem.Reason.IDENTITY_CONFLICT) {
                throw conflict;
            }
            // The failed insert transaction has already rolled back before this lookup.
            // A concurrent bootstrap may have committed the same unique Auth0 subject.
            try {
                return new BootstrapResult(lookup.bySubject(identity.subject()), false);
            } catch (IdentityProblem missing) {
                if (missing.reason() == IdentityProblem.Reason.USER_NOT_PROVISIONED) {
                    throw conflict;
                }
                throw missing;
            }
        }
    }
}
