package com.fixup.identityaccess.domain;

import com.fixup.identityaccess.api.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccounts {
    Optional<UserAccount> findBySubject(String subject);
    Optional<UserAccount> findById(UUID id);
    List<UserAccount> findAllById(Collection<UUID> ids);
    List<UserAccount> findActiveWithRole(Role role);
    Optional<UserAccount> findByIdForUpdate(UUID id);
    UserAccount create(UserAccount user);
    UserAccount save(UserAccount user);
}
