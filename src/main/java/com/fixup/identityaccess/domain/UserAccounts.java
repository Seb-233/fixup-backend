package com.fixup.identityaccess.domain;

import java.util.Optional;
import java.util.UUID;

public interface UserAccounts {
    Optional<UserAccount> findBySubject(String subject);
    Optional<UserAccount> findById(UUID id);
    Optional<UserAccount> findByIdForUpdate(UUID id);
    UserAccount create(UserAccount user);
    UserAccount save(UserAccount user);
}
