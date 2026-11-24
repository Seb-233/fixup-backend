package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.domain.IdentityProblem;
import com.fixup.identityaccess.domain.UserAccount;
import com.fixup.identityaccess.domain.UserAccounts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaUserAccounts implements UserAccounts {
    private final UserJpaRepository repository;
    @PersistenceContext
    private EntityManager entityManager;

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
        // RoleAssignments holds the user row lock. Add only missing roles, without merging the
        // account or deleting/recreating its collection (which would require excessive privileges).
        var current = repository.findById(user.id()).orElseThrow().toDomain();
        for (var role : user.roles()) {
            if (!current.roles().contains(role)) {
                entityManager.createNativeQuery("INSERT INTO user_roles(user_id, role) VALUES (:id, :role)")
                        .setParameter("id", user.id()).setParameter("role", role.name()).executeUpdate();
            }
        }
        entityManager.createNativeQuery("UPDATE users SET updated_at = :updated WHERE id = :id")
                .setParameter("updated", user.updatedAt()).setParameter("id", user.id()).executeUpdate();
        var entity = repository.getReferenceById(user.id());
        entityManager.refresh(entity);
        return entity.toDomain();
    }
}
