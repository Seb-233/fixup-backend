package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.domain.ExternalIdentity;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserRegistration {
    private final UserAccounts accounts;
    private final IdentitySession session;

    public UserRegistration(UserAccounts accounts, IdentitySession session) {
        this.accounts = accounts;
        this.session = session;
    }

    public BootstrapResult register(ExternalIdentity identity) {
        return session.execute(identity.subject(), () -> registerInSession(identity));
    }

    // Package-private for the demo-only startup seeder, which already owns a trusted transaction.
    BootstrapResult registerInSession(ExternalIdentity identity) {
        var existing = accounts.findBySubject(identity.subject());
        if (existing.isPresent()) {
            existing.get().requireActive();
            return new BootstrapResult(existing.get(), false);
        }
        var now = Instant.now();
        var user = new UserAccount(UUID.randomUUID(), identity.subject(), identity.email(),
                identity.displayName(), UserStatus.ACTIVE, Set.of(), now, now);
        return new BootstrapResult(accounts.create(user), true);
    }
}
