package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByAuth0Subject(String subject);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findForUpdate(@Param("id") UUID id);

    @Query("select distinct u from UserEntity u join u.roles r where r = :role and u.status = :status")
    List<UserEntity> findActiveWithRole(@Param("role") Role role, @Param("status") UserStatus status);
}
