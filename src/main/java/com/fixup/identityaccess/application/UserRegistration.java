package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.domain.ExternalIdentity;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistration {
    private final UserAccounts accounts;

    public UserRegistration(UserAccounts accounts) {
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BootstrapResult register(ExternalIdentity identity) {
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
