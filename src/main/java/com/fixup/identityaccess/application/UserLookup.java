package com.fixup.identityaccess.application;

import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserLookup {
    private final UserAccounts accounts;
    private final IdentitySession session;

    public UserLookup(UserAccounts accounts, IdentitySession session) {
        this.accounts = accounts;
        this.session = session;
    }

    public UserAccount bySubject(String subject) {
        return session.read(subject, () -> active(accounts.findBySubject(subject)
                .orElseThrow(() -> new IdentityProblem(IdentityProblem.Reason.USER_NOT_PROVISIONED))));
    }

    @Transactional(readOnly = true)
    public UserAccount byId(UUID id) {
        return active(accounts.findById(id)
                .orElseThrow(() -> new IdentityProblem(IdentityProblem.Reason.USER_NOT_PROVISIONED)));
    }

    private UserAccount active(UserAccount user) {
        user.requireActive();
        return user;
    }
}
