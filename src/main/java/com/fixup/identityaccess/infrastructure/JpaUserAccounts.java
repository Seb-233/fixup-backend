package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

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
