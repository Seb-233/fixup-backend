package com.fixup.identityaccess.application;

import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserLookup {
    private final UserAccounts accounts;

    public UserLookup(UserAccounts accounts) {
        this.accounts = accounts;
    }

    public UserAccount bySubject(String subject) {
        return active(accounts.findBySubject(subject)
                .orElseThrow(() -> new IdentityProblem(IdentityProblem.Reason.USER_NOT_PROVISIONED)));
    }

    public UserAccount byId(UUID id) {
        return active(accounts.findById(id)
                .orElseThrow(() -> new IdentityProblem(IdentityProblem.Reason.USER_NOT_PROVISIONED)));
    }

    private UserAccount active(UserAccount user) {
        user.requireActive();
        return user;
    }
}
