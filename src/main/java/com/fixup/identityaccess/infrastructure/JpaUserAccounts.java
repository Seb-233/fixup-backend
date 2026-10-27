package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaUserAccounts implements UserAccounts {
    private final UserJpaRepository repository;

    JpaUserAccounts(UserJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserAccount> findBySubject(String subject) {
        return repository.findByAuth0Subject(subject).map(UserEntity::toDomain);
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return repository.findById(id).map(UserEntity::toDomain);
    }

    @Override
    public List<UserAccount> findAllById(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().map(UserEntity::toDomain).toList();
    }

    @Override
    public List<UserAccount> findActiveWithRole(Role role) {
        return repository.findActiveWithRole(role, UserStatus.ACTIVE).stream().map(UserEntity::toDomain).toList();
    }

    @Override
    public Optional<UserAccount> findByIdForUpdate(UUID id) {
        return repository.findForUpdate(id).map(UserEntity::toDomain);
    }

    @Override
    public UserAccount create(UserAccount user) {
        try {
            return repository.saveAndFlush(UserEntity.from(user)).toDomain();
        } catch (DataIntegrityViolationException conflict) {
            throw new IdentityProblem(IdentityProblem.Reason.IDENTITY_CONFLICT);
        }
    }

    @Override
    public UserAccount save(UserAccount user) {
        return repository.saveAndFlush(UserEntity.from(user)).toDomain();
    }
}
