package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.domain.UserAccount;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserEntity {
    @Id
    private UUID id;
    @Column(name = "auth0_subject", nullable = false, unique = true, updatable = false, length = 255)
    private String auth0Subject;
    @Column(length = 320)
    private String email;
    @Column(name = "display_name", length = 200)
    private String displayName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;
    @ElementCollection
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Set<Role> roles = new HashSet<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserEntity() {
    }

    static UserEntity from(UserAccount user) {
        var entity = new UserEntity();
        entity.id = user.id();
        entity.auth0Subject = user.externalSubject();
        entity.email = user.email();
        entity.displayName = user.displayName();
        entity.status = user.status();
        entity.roles = new HashSet<>(user.roles());
        entity.createdAt = user.createdAt();
        entity.updatedAt = user.updatedAt();
        return entity;
    }

    UserAccount toDomain() {
        return new UserAccount(id, auth0Subject, email, displayName, status, roles, createdAt, updatedAt);
    }
}
